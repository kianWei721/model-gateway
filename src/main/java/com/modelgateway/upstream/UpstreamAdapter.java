package com.modelgateway.upstream;

import com.modelgateway.catalog.Protocol;
import com.modelgateway.catalog.UpstreamModel;
import java.time.Duration;
import tools.jackson.databind.node.ObjectNode;

public interface UpstreamAdapter {

    Protocol protocol();

    /**
     * Translates an OpenAI-shaped request for this upstream without any network I/O.
     *
     * @throws UpstreamFailure of kind {@code INCOMPATIBLE} when the request cannot be expressed
     */
    PreparedCall prepare(UpstreamModel model, ObjectNode openAiRequest);

    /** One HTTP call, no retries: retry and failover decisions belong to the gateway. */
    UpstreamResult execute(PreparedCall call, String apiKey, Duration timeout);
}
