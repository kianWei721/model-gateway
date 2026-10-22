package com.modelgateway.catalog;

import com.modelgateway.config.GatewayProperties;
import com.modelgateway.config.GatewayProperties.ModelConfig;
import com.modelgateway.config.GatewayProperties.ProviderConfig;
import com.modelgateway.upstream.RequestFields;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validated runtime view of providers, models and routes. Construction fails fast: a gateway
 * that starts with a half-valid routing table fails over to the wrong place at 3 a.m. instead.
 */
public final class Catalog {

    private final Map<String, Provider> providers;
    private final Map<String, UpstreamModel> models;
    private final Map<String, List<UpstreamModel>> routes;

    public Catalog(GatewayProperties properties) {
        Set<String> allowedHosts =
                properties.allowedUpstreamHosts().stream()
                        .map(host -> host.trim().toLowerCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet());

        Map<String, Provider> providerMap = new LinkedHashMap<>();
        properties.providers().forEach(
                (name, config) -> providerMap.put(name, provider(name, config, allowedHosts)));
        this.providers = Map.copyOf(providerMap);

        Map<String, UpstreamModel> modelMap = new LinkedHashMap<>();
        for (ModelConfig config : properties.models()) {
            UpstreamModel model = model(config, providerMap);
            if (modelMap.putIfAbsent(model.id(), model) != null) {
                throw invalid("model " + model.id() + " is declared twice");
            }
        }
        this.models = Map.copyOf(modelMap);

        Map<String, List<UpstreamModel>> routeMap = new LinkedHashMap<>();
        properties.routes().forEach(
                (route, candidateIds) -> {
                    if (candidateIds == null || candidateIds.isEmpty()) {
                        throw invalid("route " + route + " has no candidates");
                    }
                    List<UpstreamModel> candidates = new ArrayList<>();
                    for (String id : candidateIds) {
                        UpstreamModel model = modelMap.get(id);
                        if (model == null) {
                            throw invalid("route " + route + " references unknown model " + id);
                        }
                        candidates.add(model);
                    }
                    routeMap.put(route, List.copyOf(candidates));
                });
        this.routes = Map.copyOf(routeMap);
    }

    public Optional<List<UpstreamModel>> route(String name) {
        return Optional.ofNullable(routes.get(name));
    }

    public Map<String, List<UpstreamModel>> routes() {
        return routes;
    }

    public Optional<UpstreamModel> model(String id) {
        return Optional.ofNullable(models.get(id));
    }

    public Map<String, UpstreamModel> models() {
        return models;
    }

    public Map<String, Provider> providers() {
        return providers;
    }

    /**
     * Upstream hosts must be on the allowlist: official vendor or cloud-hosted endpoints, never a
     * third-party relay that could quietly serve a cheaper model. Plain HTTP is only accepted for
     * loopback addresses (local fakes in tests).
     */
    private static Provider provider(String name, ProviderConfig config, Set<String> allowedHosts) {
        if (config.protocol() == null || config.region() == null || isBlank(config.baseUrl())) {
            throw invalid("provider " + name + " needs protocol, region and base-url");
        }
        URI baseUrl = URI.create(config.baseUrl().replaceAll("/+$", ""));
        String host = baseUrl.getHost() == null ? "" : baseUrl.getHost().toLowerCase(Locale.ROOT);
        if (!allowedHosts.contains(host)) {
            throw invalid("provider " + name + " host " + host + " is not in allowed-upstream-hosts");
        }
        if (!"https".equals(baseUrl.getScheme()) && !isLoopback(host)) {
            throw invalid("provider " + name + " must use https");
        }
        return new Provider(
                name,
                config.protocol(),
                baseUrl,
                config.region(),
                proxy(name, config.proxy()),
                config.connectTimeout(),
                config.bootstrapKeyEnv());
    }

    private static UpstreamModel model(ModelConfig config, Map<String, Provider> providers) {
        Provider provider = providers.get(config.provider());
        if (provider == null) {
            throw invalid("model " + config.model() + " references unknown provider " + config.provider());
        }
        if (isBlank(config.model())) {
            throw invalid("a model of provider " + config.provider() + " has no name");
        }
        String id = config.provider() + "/" + config.model();
        BigDecimal input = requirePrice(id, "input-per-million", config.inputPerMillion());
        BigDecimal output = requirePrice(id, "output-per-million", config.outputPerMillion());
        BigDecimal cachedInput =
                config.cachedInputPerMillion() == null
                        ? input
                        : requirePrice(id, "cached-input-per-million", config.cachedInputPerMillion());
        BigDecimal cacheWrite =
                config.cacheWritePerMillion() == null
                        ? input
                        : requirePrice(id, "cache-write-per-million", config.cacheWritePerMillion());
        String currency = config.currency().trim().toUpperCase(Locale.ROOT);
        if (!currency.matches("[A-Z]{3}")) {
            throw invalid("model " + id + " currency must be an ISO code");
        }
        if (config.maxConcurrency() < 1 || config.defaultMaxTokens() < 1) {
            throw invalid("model " + id + " needs positive max-concurrency and default-max-tokens");
        }
        if (config.refusalFallback() && provider.protocol() != Protocol.ANTHROPIC) {
            throw invalid("model " + id + ": refusal-fallback is an Anthropic-only option");
        }
        List<String> aliases =
                config.servedModelAliases() == null ? List.of() : List.copyOf(config.servedModelAliases());
        return new UpstreamModel(
                provider,
                config.model(),
                new Pricing(currency, input, cachedInput, cacheWrite, output),
                aliases,
                config.supportsTools(),
                config.forwardSampling(),
                config.maxConcurrency(),
                config.defaultMaxTokens(),
                config.refusalFallback(),
                config.supportsJsonOutput(),
                extraBody(id, provider, config.extraBody()));
    }

    /**
     * Typed vendor extensions for one model. They may not shadow standard fields: the operator
     * adds vendor switches, it does not get to rewrite what the caller asked for.
     */
    private static Map<String, Object> extraBody(String id, Provider provider, Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        if (provider.protocol() != Protocol.OPENAI) {
            throw invalid("model " + id + ": extra-body is only supported for OPENAI protocol upstreams");
        }
        Map<String, Object> typed = new LinkedHashMap<>();
        raw.forEach(
                (field, value) -> {
                    if (RequestFields.STANDARD.contains(field)) {
                        throw invalid("model " + id + ": extra-body may not set standard field " + field);
                    }
                    typed.put(field, typedValue(value));
                });
        return typed;
    }

    /** YAML map values arrive as strings; restore booleans and numbers before they reach JSON. */
    private static Object typedValue(String value) {
        String trimmed = value == null ? "" : value.trim();
        if ("true".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed)) {
            return Boolean.parseBoolean(trimmed);
        }
        if (trimmed.matches("-?\\d+(\\.\\d+)?")) {
            return new BigDecimal(trimmed);
        }
        return trimmed;
    }

    private static Optional<InetSocketAddress> proxy(String provider, String value) {
        if (isBlank(value)) {
            return Optional.empty();
        }
        int colon = value.lastIndexOf(':');
        if (colon <= 0) {
            throw invalid("provider " + provider + " proxy must be host:port");
        }
        return Optional.of(
                InetSocketAddress.createUnresolved(
                        value.substring(0, colon).trim(),
                        Integer.parseInt(value.substring(colon + 1).trim())));
    }

    private static BigDecimal requirePrice(String id, String field, BigDecimal value) {
        if (value == null || value.signum() < 0) {
            throw invalid("model " + id + " needs a non-negative " + field);
        }
        return value;
    }

    private static boolean isLoopback(String host) {
        try {
            return "localhost".equals(host) || InetAddress.getByName(host).isLoopbackAddress();
        } catch (Exception exception) {
            return false;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static IllegalStateException invalid(String message) {
        return new IllegalStateException("Invalid gateway configuration: " + message);
    }
}
