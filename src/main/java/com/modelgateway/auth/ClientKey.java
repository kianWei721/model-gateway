package com.modelgateway.auth;

import java.time.Instant;
import java.util.Set;

/**
 * A business caller's credential. Usage and cost are attributed to {@code team} and
 * {@code project}; {@code allowOverseas} is the data-residency switch and defaults to false.
 */
public record ClientKey(
        String id,
        String keyPrefix,
        String team,
        String project,
        Set<String> allowedRoutes,
        boolean allowOverseas,
        int rpm,
        Status status,
        Instant createdAt) {

    public enum Status {
        ACTIVE,
        REVOKED
    }

    public ClientKey {
        allowedRoutes = Set.copyOf(allowedRoutes);
    }
}
