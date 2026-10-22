package com.modelgateway.core;

import java.util.Map;

/** A request-level failure returned to the caller in OpenAI error shape. */
public class GatewayException extends RuntimeException {

    private final int status;
    private final String code;
    private final Map<String, String> headers;

    public GatewayException(int status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public GatewayException(int status, String code, String message, Map<String, String> headers) {
        super(message);
        this.status = status;
        this.code = code;
        this.headers = Map.copyOf(headers);
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, String> headers() {
        return headers;
    }

    /** Same failure with extra response headers; headers already set by the thrower win. */
    public GatewayException withHeaders(Map<String, String> extra) {
        Map<String, String> merged = new java.util.LinkedHashMap<>(extra);
        merged.putAll(headers);
        return new GatewayException(status, code, getMessage(), merged);
    }
}
