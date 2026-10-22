package com.modelgateway.upstream;

import tools.jackson.databind.node.ObjectNode;

/**
 * @param response the answer in OpenAI chat.completion shape, whatever the upstream protocol
 * @param servedModel the model name the upstream reported; compared, never trusted for pricing
 * @param upstreamFallback the upstream itself re-ran the request on another model
 */
public record UpstreamResult(
        ObjectNode response, String servedModel, TokenUsage usage, boolean upstreamFallback) {}
