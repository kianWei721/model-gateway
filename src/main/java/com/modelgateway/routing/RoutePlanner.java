package com.modelgateway.routing;

import com.modelgateway.auth.ClientKey;
import com.modelgateway.catalog.Catalog;
import com.modelgateway.catalog.Protocol;
import com.modelgateway.catalog.Region;
import com.modelgateway.catalog.UpstreamModel;
import com.modelgateway.core.GatewayException;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a declared route into the ordered list of upstreams this caller may use for this request.
 *
 * <p>Complexity is declared by the caller (route name), not inferred by a model: routing must be
 * deterministic, auditable and add no latency. Filters never reorder candidates.
 */
public final class RoutePlanner {

    private final Catalog catalog;

    public RoutePlanner(Catalog catalog) {
        this.catalog = catalog;
    }

    public List<UpstreamModel> plan(String route, ClientKey key, RequestTraits traits) {
        List<UpstreamModel> declared =
                catalog.route(route)
                        .orElseThrow(
                                () ->
                                        new GatewayException(
                                                400, "UNKNOWN_ROUTE", "Unknown route '" + route + "'"));
        if (!key.allowedRoutes().contains(route)) {
            throw new GatewayException(
                    403, "ROUTE_NOT_ALLOWED", "This key may not use route '" + route + "'");
        }

        List<UpstreamModel> eligible = new ArrayList<>();
        List<String> excluded = new ArrayList<>();
        for (UpstreamModel candidate : declared) {
            // Data residency: an overseas upstream is never used, not even as a fallback, unless the
            // key explicitly allows data to leave the country.
            if (candidate.region() == Region.OVERSEAS && !key.allowOverseas()) {
                excluded.add(candidate.id() + " (overseas, key not cleared)");
            } else if (traits.usesTools() && !candidate.supportsTools()) {
                excluded.add(candidate.id() + " (no tool calling)");
            } else if (traits.usesJsonOutput() && !candidate.supportsJsonOutput()) {
                // A caller that parses JSON must not fail over to a model that ignores response_format.
                excluded.add(candidate.id() + " (no JSON output)");
            } else if (traits.usesLogitControls()
                    && candidate.provider().protocol() != Protocol.OPENAI) {
                excluded.add(candidate.id() + " (no logprobs / logit_bias)");
            } else {
                eligible.add(candidate);
            }
        }
        if (eligible.isEmpty()) {
            throw new GatewayException(
                    400,
                    "NO_ELIGIBLE_UPSTREAM",
                    "No upstream in route '" + route + "' can serve this request: " + String.join(", ", excluded));
        }
        return List.copyOf(eligible);
    }

    public record RequestTraits(boolean usesTools, boolean usesJsonOutput, boolean usesLogitControls) {

        public RequestTraits(boolean usesTools, boolean usesJsonOutput) {
            this(usesTools, usesJsonOutput, false);
        }
    }
}
