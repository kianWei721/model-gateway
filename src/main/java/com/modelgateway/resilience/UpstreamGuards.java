package com.modelgateway.resilience;

import com.modelgateway.catalog.Catalog;
import com.modelgateway.catalog.UpstreamModel;
import com.modelgateway.upstream.UpstreamFailure;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * Per provider+model protection: a circuit breaker for upstream health and a concurrency cap.
 *
 * <p>The cap matters because virtual threads remove the natural back-pressure of a thread pool:
 * without it a slow upstream would collect an unbounded number of waiting requests.
 */
public final class UpstreamGuards {

    public record Status(String state, float failureRate, int inFlight, int maxConcurrency) {}

    private final CircuitBreakerRegistry registry;
    private final Map<String, Semaphore> permits = new LinkedHashMap<>();
    private final Map<String, Integer> limits = new LinkedHashMap<>();

    public UpstreamGuards(Catalog catalog, CircuitBreakerConfig config) {
        this.registry = CircuitBreakerRegistry.of(config);
        for (UpstreamModel model : catalog.models().values()) {
            permits.put(model.id(), new Semaphore(model.maxConcurrency()));
            limits.put(model.id(), model.maxConcurrency());
        }
    }

    /**
     * Time-based window because internal traffic is low and bursty: a count-based window of the
     * last N calls could stay "unhealthy" for hours on a quiet route. Slow calls are not counted
     * as failures; long answers are normal for LLMs and the deadline handles real hangs.
     */
    public static CircuitBreakerConfig defaultConfig() {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.TIME_BASED)
                .slidingWindowSize(60)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(2)
                .slowCallDurationThreshold(Duration.ofMinutes(5))
                .slowCallRateThreshold(100)
                .recordException(UpstreamGuards::countsAgainstUpstream)
                .ignoreException(throwable -> !countsAgainstUpstream(throwable))
                .build();
    }

    public CircuitBreaker breaker(UpstreamModel model) {
        return registry.circuitBreaker(model.id());
    }

    public boolean tryEnter(UpstreamModel model) {
        return permits.get(model.id()).tryAcquire();
    }

    public void exit(UpstreamModel model) {
        permits.get(model.id()).release();
    }

    /** Operator override after a confirmed recovery, so traffic does not wait out the open window. */
    public boolean reset(String modelId) {
        if (!permits.containsKey(modelId)) {
            return false;
        }
        registry.circuitBreaker(modelId).reset();
        return true;
    }

    public Map<String, Status> snapshot() {
        Map<String, Status> snapshot = new LinkedHashMap<>();
        permits.forEach(
                (id, semaphore) -> {
                    CircuitBreaker breaker = registry.circuitBreaker(id);
                    snapshot.put(
                            id,
                            new Status(
                                    breaker.getState().name(),
                                    breaker.getMetrics().getFailureRate(),
                                    limits.get(id) - semaphore.availablePermits(),
                                    limits.get(id)));
                });
        return snapshot;
    }

    private static boolean countsAgainstUpstream(Throwable throwable) {
        return throwable instanceof UpstreamFailure failure && failure.kind().countsAgainstUpstream();
    }
}
