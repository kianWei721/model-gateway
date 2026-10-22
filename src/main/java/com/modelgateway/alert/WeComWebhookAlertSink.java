package com.modelgateway.alert;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** WeCom group-robot text message, sent off the request thread. */
public final class WeComWebhookAlertSink implements AlertSink {

    private static final Logger log = LoggerFactory.getLogger(WeComWebhookAlertSink.class);

    private final URI webhook;
    private final JsonMapper jsonMapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public WeComWebhookAlertSink(String webhook, JsonMapper jsonMapper) {
        this.webhook = URI.create(webhook);
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void send(Alert alert) {
        ObjectNode body = jsonMapper.createObjectNode();
        body.put("msgtype", "text");
        body.putObject("text")
                .put("content", "[model-gateway][" + alert.severity() + "] " + alert.type() + "\n" + alert.message()
                        + (alert.attributes().isEmpty() ? "" : "\n" + alert.attributes()));
        HttpRequest request =
                HttpRequest.newBuilder(webhook)
                        .timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(body)))
                        .build();
        executor.execute(
                () -> {
                    try {
                        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                        if (response.statusCode() != 200) {
                            log.warn("WeCom webhook answered HTTP {}", response.statusCode());
                        }
                    } catch (Exception exception) {
                        log.warn("WeCom webhook failed: {}", exception.toString());
                    }
                });
    }
}
