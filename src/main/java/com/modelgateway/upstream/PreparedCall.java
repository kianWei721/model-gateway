package com.modelgateway.upstream;

import com.modelgateway.catalog.UpstreamModel;
import tools.jackson.databind.node.ObjectNode;

/** A request already translated into the upstream's protocol, minus credentials. */
public record PreparedCall(UpstreamModel model, ObjectNode body, int maxOutputTokens) {}
