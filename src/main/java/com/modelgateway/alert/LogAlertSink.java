package com.modelgateway.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LogAlertSink implements AlertSink {

    private static final Logger log = LoggerFactory.getLogger("gateway.alerts");

    @Override
    public void send(Alert alert) {
        switch (alert.severity()) {
            case CRITICAL -> log.error("[{}] {} {}", alert.type(), alert.message(), alert.attributes());
            case WARNING -> log.warn("[{}] {} {}", alert.type(), alert.message(), alert.attributes());
            default -> log.info("[{}] {} {}", alert.type(), alert.message(), alert.attributes());
        }
    }
}
