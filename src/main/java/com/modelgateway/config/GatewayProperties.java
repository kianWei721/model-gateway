package com.modelgateway.config;

import com.modelgateway.catalog.Protocol;
import com.modelgateway.catalog.Region;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Raw configuration. {@link com.modelgateway.catalog.Catalog} validates it and turns it into the
 * runtime model; nothing else should read these records directly.
 */
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(
        @DefaultValue("45s") Duration deadline,
        @DefaultValue("40s") Duration attemptTimeout,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("Asia/Shanghai") ZoneId billingZone,
        @DefaultValue("") String adminToken,
        @DefaultValue Secrets secrets,
        @DefaultValue Alerts alerts,
        List<String> allowedUpstreamHosts,
        Map<String, ProviderConfig> providers,
        List<ModelConfig> models,
        Map<String, List<String>> routes,
        Map<String, Map<String, BigDecimal>> dailyBudgets) {

    public GatewayProperties {
        allowedUpstreamHosts = allowedUpstreamHosts == null ? List.of() : List.copyOf(allowedUpstreamHosts);
        providers = providers == null ? Map.of() : Map.copyOf(providers);
        models = models == null ? List.of() : List.copyOf(models);
        routes = routes == null ? Map.of() : Map.copyOf(routes);
        dailyBudgets = dailyBudgets == null ? Map.of() : Map.copyOf(dailyBudgets);
    }

    /**
     * @param masterKeys comma separated {@code version:base64Key} entries, 32-byte AES keys
     * @param currentMasterKeyVersion version used for new encryptions; older ones stay readable
     */
    public record Secrets(
            @DefaultValue("") String masterKeys,
            @DefaultValue("v1") String currentMasterKeyVersion) {}

    /**
     * @param wecomWebhookUrl WeCom group-robot webhook; alerts only go to the log when blank
     * @param budgetThresholds fractions of the daily budget that raise an alert once per day
     */
    public record Alerts(
            @DefaultValue("") String wecomWebhookUrl,
            @DefaultValue({"0.8", "1.0"}) List<BigDecimal> budgetThresholds,
            @DefaultValue("10m") Duration dedupWindow) {}

    /**
     * @param proxy optional {@code host:port} HTTP CONNECT proxy, typically for overseas egress
     * @param bootstrapKeyEnv environment variable whose value is imported as the first key when the
     *     provider has no active key yet; convenient for local runs, never required
     */
    public record ProviderConfig(
            Protocol protocol,
            String baseUrl,
            Region region,
            @DefaultValue("") String proxy,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("") String bootstrapKeyEnv) {}

    /**
     * Prices are per million tokens in {@code currency}. {@code cachedInputPerMillion} and
     * {@code cacheWritePerMillion} default to the input price when omitted.
     *
     * @param servedModelAliases model names the upstream may report back for this model, besides
     *     dated snapshots of {@code model} itself
     * @param forwardSampling whether temperature/top_p are passed on; some models reject them
     * @param refusalFallback Anthropic only: let the upstream re-run refused requests on another
     *     model. Off by default because the gateway promises that the configured model serves.
     */
    public record ModelConfig(
            String provider,
            String model,
            @DefaultValue("CNY") String currency,
            BigDecimal inputPerMillion,
            BigDecimal cachedInputPerMillion,
            BigDecimal cacheWritePerMillion,
            BigDecimal outputPerMillion,
            List<String> servedModelAliases,
            @DefaultValue("true") boolean supportsTools,
            @DefaultValue("true") boolean forwardSampling,
            @DefaultValue("16") int maxConcurrency,
            @DefaultValue("4096") int defaultMaxTokens,
            @DefaultValue("false") boolean refusalFallback) {}
}
