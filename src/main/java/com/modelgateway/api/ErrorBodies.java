package com.modelgateway.api;

import java.util.Map;

/** OpenAI-style error envelope, so existing SDK error handling works against the gateway. */
public final class ErrorBodies {

    private ErrorBodies() {}

    public static Map<String, Object> of(String code, String message) {
        return Map.of("error", Map.of("type", "gateway_error", "code", code, "message", message));
    }
}
