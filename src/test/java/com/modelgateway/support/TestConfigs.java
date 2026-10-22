package com.modelgateway.support;

import com.modelgateway.catalog.Protocol;
import com.modelgateway.catalog.Region;
import com.modelgateway.config.GatewayProperties;
import com.modelgateway.config.GatewayProperties.Alerts;
import com.modelgateway.config.GatewayProperties.ModelConfig;
import com.modelgateway.config.GatewayProperties.ProviderConfig;
import com.modelgateway.config.GatewayProperties.Secrets;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A three-provider setup mirroring the demo config, pointed at a fake upstream. */
public final class TestConfigs {

    private TestConfigs() {}

    public static GatewayProperties standard(String fakeBase) {
        Map<String, ProviderConfig> providers = new LinkedHashMap<>();
        providers.put("dashscope", provider(Protocol.OPENAI, fakeBase + "/dashscope/v1", Region.DOMESTIC));
        providers.put("deepseek", provider(Protocol.OPENAI, fakeBase + "/deepseek/v1", Region.DOMESTIC));
        providers.put("anthropic", provider(Protocol.ANTHROPIC, fakeBase + "/anthropic/v1", Region.OVERSEAS));

        List<ModelConfig> models =
                List.of(
                        model("dashscope", "qwen-plus", "CNY", "0.80", "0.16", "2.00", true, true, false),
                        model("deepseek", "deepseek-chat", "CNY", "2.00", "0.50", "8.00", true, true, false),
                        model("deepseek", "deepseek-reasoner", "CNY", "4.00", "1.00", "16.00", false, true, false),
                        model("anthropic", "claude-opus-5-5", "USD", "4.00", "0.20", "20.00", false, false, false));

        Map<String, List<String>> routes = new LinkedHashMap<>();
        routes.put("standard", List.of("dashscope/qwen-plus", "deepseek/deepseek-chat"));
        routes.put("reasoning", List.of("anthropic/claude-opus-5-5", "deepseek/deepseek-reasoner"));

        return new GatewayProperties(
                Duration.ofSeconds(10),
                Duration.ofSeconds(5),
                3,
                ZoneId.of("Asia/Shanghai"),
                "admin-secret",
                new Secrets(masterKeys(), "v1"),
                new Alerts("", List.of(new BigDecimal("0.8"), BigDecimal.ONE), Duration.ofMinutes(10)),
                List.of("127.0.0.1"),
                providers,
                models,
                routes,
                Map.of("finance", Map.of("CNY", new BigDecimal("1.00"))));
    }

    public static String masterKeys() {
        return "v1:AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    }

    public static ProviderConfig provider(Protocol protocol, String baseUrl, Region region) {
        return new ProviderConfig(protocol, baseUrl, region, "", Duration.ofSeconds(2), "");
    }

    public static ModelConfig model(
            String provider,
            String model,
            String currency,
            String input,
            String cachedInput,
            String output,
            boolean supportsTools,
            boolean forwardSampling,
            boolean refusalFallback) {
        return new ModelConfig(
                provider,
                model,
                currency,
                new BigDecimal(input),
                new BigDecimal(cachedInput),
                null,
                new BigDecimal(output),
                List.of(),
                supportsTools,
                forwardSampling,
                4,
                1024,
                refusalFallback);
    }
}
