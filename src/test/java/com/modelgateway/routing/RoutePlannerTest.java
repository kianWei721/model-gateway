package com.modelgateway.routing;

import static org.junit.jupiter.api.Assertions.*;

import com.modelgateway.auth.ClientKey;
import com.modelgateway.catalog.Catalog;
import com.modelgateway.catalog.UpstreamModel;
import com.modelgateway.config.GatewayProperties;
import com.modelgateway.core.GatewayException;
import com.modelgateway.routing.RoutePlanner.RequestTraits;
import com.modelgateway.support.TestConfigs;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RoutePlannerTest {

    private final Catalog catalog = new Catalog(TestConfigs.standard("http://127.0.0.1:1"));
    private final RoutePlanner planner = new RoutePlanner(catalog);

    @Test
    void domesticKeyShouldNeverReachOverseasUpstreamEvenAsFallback() {
        List<String> plan = ids(planner.plan("reasoning", key(false), new RequestTraits(false, false)));

        assertEquals(List.of("deepseek/deepseek-reasoner"), plan);
    }

    @Test
    void clearedKeyShouldKeepDeclaredOrder() {
        List<String> plan = ids(planner.plan("reasoning", key(true), new RequestTraits(false, false)));

        assertEquals(List.of("anthropic/claude-opus-5-5", "deepseek/deepseek-reasoner"), plan);
    }

    @Test
    void jsonRequestsShouldNeverFailOverToAModelThatIgnoresResponseFormat() {
        List<String> plan = ids(planner.plan("reasoning", key(true), new RequestTraits(false, true)));

        assertEquals(List.of("deepseek/deepseek-reasoner"), plan);
    }

    @Test
    void toolRequestsShouldSkipUpstreamsWithoutToolCalling() {
        GatewayException exception =
                assertThrows(
                        GatewayException.class,
                        () -> planner.plan("reasoning", key(true), new RequestTraits(true, false)));

        assertEquals("NO_ELIGIBLE_UPSTREAM", exception.code());
        assertTrue(exception.getMessage().contains("anthropic/claude-opus-5-5 (no tool calling)"));
        assertEquals(
                List.of("dashscope/qwen-plus", "deepseek/deepseek-chat"),
                ids(planner.plan("standard", key(false), new RequestTraits(true, false))));
    }

    @Test
    void shouldRejectUnknownAndForbiddenRoutes() {
        assertEquals(
                "UNKNOWN_ROUTE",
                assertThrows(GatewayException.class, () -> planner.plan("gpt-4", key(true), new RequestTraits(false, false)))
                        .code());

        ClientKey standardOnly =
                new ClientKey("k", "gk-x", "finance", "tax", Set.of("standard"), true, 60, ClientKey.Status.ACTIVE, Instant.EPOCH);
        GatewayException forbidden =
                assertThrows(
                        GatewayException.class,
                        () -> planner.plan("reasoning", standardOnly, new RequestTraits(false, false)));
        assertEquals(403, forbidden.status());
    }

    @Test
    void catalogShouldRejectHostsOutsideTheAllowlist() {
        GatewayProperties base = TestConfigs.standard("http://127.0.0.1:1");
        GatewayProperties relay =
                new GatewayProperties(
                        base.deadline(),
                        base.attemptTimeout(),
                        base.maxAttempts(),
                        base.billingZone(),
                        base.adminToken(),
                        base.secrets(),
                        base.alerts(),
                        base.allowedUpstreamHosts(),
                        Map.of(
                                "cheap-relay",
                                TestConfigs.provider(
                                        com.modelgateway.catalog.Protocol.OPENAI,
                                        "https://api.some-relay.example/v1",
                                        com.modelgateway.catalog.Region.OVERSEAS)),
                        List.of(),
                        Map.of(),
                        Map.of());

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> new Catalog(relay));
        assertTrue(exception.getMessage().contains("not in allowed-upstream-hosts"));
    }

    private static ClientKey key(boolean allowOverseas) {
        return new ClientKey(
                "key-1", "gk-test", "finance", "tax-filing", Set.of("standard", "reasoning"), allowOverseas, 60,
                ClientKey.Status.ACTIVE, Instant.EPOCH);
    }

    private static List<String> ids(List<UpstreamModel> models) {
        return models.stream().map(UpstreamModel::id).toList();
    }
}
