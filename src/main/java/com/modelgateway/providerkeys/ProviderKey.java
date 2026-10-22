package com.modelgateway.providerkeys;

import java.time.Instant;

/** A vendor API key as stored: sealed, never in plaintext. */
public record ProviderKey(
        String id,
        String provider,
        String ciphertext,
        String masterKeyVersion,
        String fingerprint,
        Status status,
        Instant createdAt) {

    public enum Status {
        ACTIVE,
        DISABLED
    }
}
