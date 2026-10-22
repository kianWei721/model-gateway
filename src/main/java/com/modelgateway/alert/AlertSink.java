package com.modelgateway.alert;

@FunctionalInterface
public interface AlertSink {

    /** Must not throw into the request path; implementations swallow and log their own failures. */
    void send(Alert alert);
}
