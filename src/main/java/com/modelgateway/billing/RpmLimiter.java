package com.modelgateway.billing;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Token bucket per client key: bursts up to the per-minute allowance, refilled continuously. */
public final class RpmLimiter {

    private final Clock clock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RpmLimiter(Clock clock) {
        this.clock = clock;
    }

    public boolean tryAcquire(String keyId, int rpm) {
        Bucket bucket = buckets.computeIfAbsent(keyId, ignored -> new Bucket(rpm, clock.millis()));
        synchronized (bucket) {
            long now = clock.millis();
            bucket.tokens = Math.min(rpm, bucket.tokens + (now - bucket.updatedAt) * rpm / 60_000.0);
            bucket.updatedAt = now;
            if (bucket.tokens < 1) {
                return false;
            }
            bucket.tokens -= 1;
            return true;
        }
    }

    private static final class Bucket {
        private double tokens;
        private long updatedAt;

        private Bucket(double tokens, long updatedAt) {
            this.tokens = tokens;
            this.updatedAt = updatedAt;
        }
    }
}
