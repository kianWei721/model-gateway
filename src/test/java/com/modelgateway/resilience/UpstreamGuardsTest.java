package com.modelgateway.resilience;

import static org.junit.jupiter.api.Assertions.*;

import com.modelgateway.catalog.Catalog;
import com.modelgateway.catalog.UpstreamModel;
import com.modelgateway.support.TestConfigs;
import com.modelgateway.upstream.UpstreamFailure;
import com.modelgateway.upstream.UpstreamFailure.Kind;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class UpstreamGuardsTest {

    private final Catalog catalog = new Catalog(TestConfigs.standard("http://127.0.0.1:1"));
    private final UpstreamGuards guards = new UpstreamGuards(catalog, UpstreamGuards.defaultConfig());
    private final UpstreamModel qwen = catalog.model("dashscope/qwen-plus").orElseThrow();

    @Test
    void serverErrorsShouldOpenTheBreakerOfThatModelOnly() {
        CircuitBreaker breaker = guards.breaker(qwen);
        for (int i = 0; i < 5; i++) {
            assertTrue(breaker.tryAcquirePermission());
            breaker.onError(10, TimeUnit.MILLISECONDS, new UpstreamFailure(Kind.SERVER_ERROR, 503, "down"));
        }

        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());
        assertFalse(breaker.tryAcquirePermission());
        assertEquals(
                CircuitBreaker.State.CLOSED,
                guards.breaker(catalog.model("deepseek/deepseek-chat").orElseThrow()).getState());
    }

    @Test
    void clientSideErrorsShouldNotCountAgainstUpstreamHealth() {
        CircuitBreaker breaker = guards.breaker(qwen);
        for (Kind kind : new Kind[] {Kind.BAD_REQUEST, Kind.UNAUTHORIZED, Kind.UPSTREAM_REJECTED}) {
            for (int i = 0; i < 5; i++) {
                assertTrue(breaker.tryAcquirePermission());
                breaker.onError(10, TimeUnit.MILLISECONDS, new UpstreamFailure(kind, 400, "client"));
            }
        }

        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
        assertEquals(0, breaker.getMetrics().getNumberOfFailedCalls());
    }

    @Test
    void concurrencyCapShouldRejectBeyondTheLimit() {
        for (int i = 0; i < qwen.maxConcurrency(); i++) {
            assertTrue(guards.tryEnter(qwen));
        }
        assertFalse(guards.tryEnter(qwen));
        assertEquals(qwen.maxConcurrency(), guards.snapshot().get(qwen.id()).inFlight());

        guards.exit(qwen);
        assertTrue(guards.tryEnter(qwen));
    }
}
