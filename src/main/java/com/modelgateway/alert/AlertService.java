package com.modelgateway.alert;

import com.modelgateway.alert.Alert.Severity;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Fan-out to sinks with de-duplication, so one broken upstream does not flood the group chat. */
public final class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);
    private static final int RECENT_LIMIT = 200;

    private final List<AlertSink> sinks;
    private final Clock clock;
    private final Duration defaultWindow;
    private final Map<String, Instant> lastRaised = new ConcurrentHashMap<>();
    private final Deque<Alert> recent = new ArrayDeque<>();

    public AlertService(List<AlertSink> sinks, Clock clock, Duration defaultWindow) {
        this.sinks = List.copyOf(sinks);
        this.clock = clock;
        this.defaultWindow = defaultWindow;
    }

    public void raise(String dedupKey, Severity severity, String type, String message, Map<String, String> attributes) {
        raise(dedupKey, defaultWindow, severity, type, message, attributes);
    }

    public void raise(
            String dedupKey,
            Duration window,
            Severity severity,
            String type,
            String message,
            Map<String, String> attributes) {
        Instant now = clock.instant();
        Instant previous = lastRaised.get(dedupKey);
        if (previous != null && previous.plus(window).isAfter(now)) {
            return;
        }
        lastRaised.put(dedupKey, now);
        Alert alert = new Alert(now, severity, type, message, attributes);
        synchronized (recent) {
            recent.addFirst(alert);
            while (recent.size() > RECENT_LIMIT) {
                recent.removeLast();
            }
        }
        for (AlertSink sink : sinks) {
            try {
                sink.send(alert);
            } catch (RuntimeException exception) {
                log.warn("alert sink {} failed: {}", sink.getClass().getSimpleName(), exception.toString());
            }
        }
    }

    public List<Alert> recent() {
        synchronized (recent) {
            return List.copyOf(recent);
        }
    }
}
