package com.modelgateway.catalog;

import java.util.List;

/** One concrete model at one provider: the unit of routing, pricing, breaking and metering. */
public record UpstreamModel(
        Provider provider,
        String model,
        Pricing pricing,
        List<String> servedModelAliases,
        boolean supportsTools,
        boolean forwardSampling,
        int maxConcurrency,
        int defaultMaxTokens,
        boolean refusalFallback) {

    public String id() {
        return provider.name() + "/" + model;
    }

    public Region region() {
        return provider.region();
    }
}
