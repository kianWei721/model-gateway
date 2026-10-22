package com.modelgateway.alert;

import java.time.Instant;
import java.util.Map;

public record Alert(Instant at, Severity severity, String type, String message, Map<String, String> attributes) {

    public enum Severity {
        INFO,
        WARNING,
        CRITICAL
    }

    public Alert {
        attributes = Map.copyOf(attributes);
    }
}
