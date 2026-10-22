package com.modelgateway.core;

import java.util.Map;
import tools.jackson.databind.node.ObjectNode;

public record GatewayResult(ObjectNode body, Map<String, String> headers) {}
