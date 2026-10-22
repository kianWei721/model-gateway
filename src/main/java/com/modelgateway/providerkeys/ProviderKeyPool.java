package com.modelgateway.providerkeys;

import com.modelgateway.catalog.Catalog;
import com.modelgateway.catalog.Provider;
import com.modelgateway.secrets.SecretCipher;
import com.modelgateway.secrets.SecretCipher.Sealed;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Decrypted vendor keys in memory, round-robin per provider.
 *
 * <p>Rotation without restart: add the new key, disable the old one; requests already holding the
 * old key finish with it. A key that answers 401 is cooled down for a while instead of disabled,
 * so a vendor-side blip cannot permanently remove a good key; disabling is an operator decision.
 */
public final class ProviderKeyPool {

    public record LeasedKey(String id, String provider, String fingerprint, String secret) {
        @Override
        public String toString() {
            return "LeasedKey[" + id + ", " + provider + ", fp=" + fingerprint + "]";
        }
    }

    private final ProviderKeyRepository repository;
    private final SecretCipher cipher;
    private final Clock clock;
    private final Duration cooldown;
    private final Map<String, Instant> coolingUntil = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> cursors = new ConcurrentHashMap<>();
    private volatile Map<String, List<LeasedKey>> active = Map.of();

    public ProviderKeyPool(ProviderKeyRepository repository, SecretCipher cipher, Clock clock, Duration cooldown) {
        this.repository = repository;
        this.cipher = cipher;
        this.clock = clock;
        this.cooldown = cooldown;
    }

    public synchronized void reload() {
        Map<String, List<LeasedKey>> byProvider = new LinkedHashMap<>();
        for (ProviderKey key : repository.findActive()) {
            String secret = cipher.open(new Sealed(key.masterKeyVersion(), key.ciphertext()), context(key.provider()));
            byProvider.computeIfAbsent(key.provider(), ignored -> new ArrayList<>())
                    .add(new LeasedKey(key.id(), key.provider(), key.fingerprint(), secret));
        }
        byProvider.replaceAll((provider, keys) -> List.copyOf(keys));
        this.active = Map.copyOf(byProvider);
    }

    /** Next usable key of the provider, skipping excluded and cooling keys. */
    public Optional<LeasedKey> next(String provider, Set<String> excludedIds) {
        List<LeasedKey> keys = active.getOrDefault(provider, List.of());
        if (keys.isEmpty()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        int start = Math.floorMod(cursors.computeIfAbsent(provider, ignored -> new AtomicInteger()).getAndIncrement(), keys.size());
        for (int offset = 0; offset < keys.size(); offset++) {
            LeasedKey candidate = keys.get((start + offset) % keys.size());
            Instant until = coolingUntil.get(candidate.id());
            if (!excludedIds.contains(candidate.id()) && (until == null || !until.isAfter(now))) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    public void coolDown(LeasedKey key) {
        coolingUntil.put(key.id(), clock.instant().plus(cooldown));
    }

    public ProviderKey add(String provider, String secret) {
        Sealed sealed = cipher.seal(secret, context(provider));
        ProviderKey key =
                new ProviderKey(
                        "pk-" + UUID.randomUUID(),
                        provider,
                        sealed.ciphertext(),
                        sealed.version(),
                        SecretCipher.fingerprint(secret),
                        ProviderKey.Status.ACTIVE,
                        clock.instant());
        repository.insert(key);
        reload();
        return key;
    }

    public boolean disable(String id) {
        boolean changed = repository.disable(id, clock.instant());
        reload();
        return changed;
    }

    /** Re-seals every stored key under the current master key version (master key rotation). */
    public int resealAll() {
        int resealed = 0;
        for (ProviderKey key : repository.findAll()) {
            if (key.masterKeyVersion().equals(cipher.currentVersion())) {
                continue;
            }
            String secret = cipher.open(new Sealed(key.masterKeyVersion(), key.ciphertext()), context(key.provider()));
            Sealed sealed = cipher.seal(secret, context(key.provider()));
            repository.reseal(key.id(), sealed.ciphertext(), sealed.version());
            resealed++;
        }
        reload();
        return resealed;
    }

    /** Imports a key from the environment for providers that have none yet (local runs). */
    public void bootstrap(Catalog catalog, Function<String, String> environment) {
        reload();
        for (Provider provider : catalog.providers().values()) {
            String variable = provider.bootstrapKeyEnv();
            if (variable == null || variable.isBlank() || active.containsKey(provider.name())) {
                continue;
            }
            String secret = environment.apply(variable);
            if (secret != null && !secret.isBlank()) {
                add(provider.name(), secret.trim());
            }
        }
    }

    public Map<String, Integer> activeCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        active.forEach((provider, keys) -> counts.put(provider, keys.size()));
        return counts;
    }

    private static String context(String provider) {
        return "provider-key:" + provider;
    }
}
