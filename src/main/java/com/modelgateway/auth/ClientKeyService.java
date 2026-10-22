package com.modelgateway.auth;

import com.modelgateway.catalog.Catalog;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Issues and verifies caller keys. Only a SHA-256 of each key is stored: the keys carry 256 bits
 * of randomness, so a fast hash is enough, and a leaked table cannot be replayed.
 */
public final class ClientKeyService {

    public record IssuedKey(String plaintext, ClientKey key) {}

    private final ClientKeyRepository repository;
    private final Catalog catalog;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public ClientKeyService(ClientKeyRepository repository, Catalog catalog, Clock clock) {
        this.repository = repository;
        this.catalog = catalog;
        this.clock = clock;
    }

    public IssuedKey issue(String team, String project, Set<String> routes, boolean allowOverseas, int rpm) {
        requireText(team, "team");
        requireText(project, "project");
        if (routes == null || routes.isEmpty()) {
            throw new IllegalArgumentException("at least one route is required");
        }
        for (String route : routes) {
            if (catalog.route(route).isEmpty()) {
                throw new IllegalArgumentException("unknown route " + route);
            }
        }
        if (rpm < 1) {
            throw new IllegalArgumentException("rpm must be positive");
        }
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String plaintext = "gk-" + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        ClientKey key =
                new ClientKey(
                        "ck-" + UUID.randomUUID(),
                        plaintext.substring(0, 10),
                        team.trim(),
                        project.trim(),
                        routes,
                        allowOverseas,
                        rpm,
                        ClientKey.Status.ACTIVE,
                        clock.instant());
        repository.insert(key, hash(plaintext));
        return new IssuedKey(plaintext, key);
    }

    public Optional<ClientKey> authenticate(String plaintext) {
        if (plaintext == null || !plaintext.startsWith("gk-")) {
            return Optional.empty();
        }
        return repository.findByHash(hash(plaintext)).filter(key -> key.status() == ClientKey.Status.ACTIVE);
    }

    public boolean revoke(String id) {
        return repository.revoke(id);
    }

    static String hash(String plaintext) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
