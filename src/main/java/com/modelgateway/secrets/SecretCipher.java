package com.modelgateway.secrets;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM envelope for provider keys at rest.
 *
 * <p>Every ciphertext records the master-key version that sealed it, so the master key can be
 * rotated: add a new version, make it current, re-seal, then retire the old one. The context
 * (e.g. the provider name) is bound as associated data, so a ciphertext copied onto another
 * provider's row fails to open. A production deployment would hold the master keys in a KMS.
 */
public final class SecretCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final Map<String, SecretKey> masterKeys;
    private final String currentVersion;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(String masterKeySpec, String currentVersion) {
        Map<String, SecretKey> keys = new LinkedHashMap<>();
        for (String entry : masterKeySpec == null ? new String[0] : masterKeySpec.split(",")) {
            if (entry.isBlank()) {
                continue;
            }
            int colon = entry.indexOf(':');
            if (colon <= 0) {
                throw new IllegalStateException("master key entries must be version:base64Key");
            }
            byte[] key = Base64.getDecoder().decode(entry.substring(colon + 1).trim());
            if (key.length != 32) {
                throw new IllegalStateException("master key " + entry.substring(0, colon).trim() + " must be 32 bytes");
            }
            keys.put(entry.substring(0, colon).trim(), new SecretKeySpec(key, "AES"));
        }
        if (!keys.containsKey(currentVersion)) {
            throw new IllegalStateException(
                    "gateway.secrets.master-keys must contain the current version '" + currentVersion + "'");
        }
        this.masterKeys = Map.copyOf(keys);
        this.currentVersion = currentVersion;
    }

    public record Sealed(String version, String ciphertext) {}

    public Sealed seal(String plaintext, String context) {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, masterKeys.get(currentVersion), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] packed = ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array();
            return new Sealed(currentVersion, Base64.getEncoder().encodeToString(packed));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("sealing failed", exception);
        }
    }

    public String open(Sealed sealed, String context) {
        SecretKey key = masterKeys.get(sealed.version());
        if (key == null) {
            throw new IllegalStateException("master key version '" + sealed.version() + "' is not loaded");
        }
        byte[] packed = Base64.getDecoder().decode(sealed.ciphertext());
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, packed, 0, IV_BYTES));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] plain = cipher.doFinal(packed, IV_BYTES, packed.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            // Wrong key, wrong context or tampering: GCM authentication fails for all three.
            throw new IllegalStateException("opening sealed secret failed", exception);
        }
    }

    public String currentVersion() {
        return currentVersion;
    }

    /** Short, non-reversible identifier for logs, ledgers and APIs. Secrets themselves never leave. */
    public static String fingerprint(String secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
