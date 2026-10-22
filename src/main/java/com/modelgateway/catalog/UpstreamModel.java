package com.modelgateway.catalog;

import java.util.List;
import java.util.Map;

/**
 * One concrete model at one provider: the unit of routing, pricing, breaking and metering.
 *
 * @param extraBody vendor-specific request fields injected by the gateway; values are already
 *     typed (Boolean, Number or String)
 */
public record UpstreamModel(
        Provider provider,
        String model,
        Pricing pricing,
        List<String> servedModelAliases,
        boolean supportsTools,
        boolean forwardSampling,
        int maxConcurrency,
        int defaultMaxTokens,
        boolean refusalFallback,
        boolean supportsJsonOutput,
        Map<String, Object> extraBody) {

    public UpstreamModel {
        servedModelAliases = List.copyOf(servedModelAliases);
        extraBody = Map.copyOf(extraBody);
    }

    public String id() {
        return provider.name() + "/" + model;
    }

    public Region region() {
        return provider.region();
    }
}
