package com.modelgateway.upstream;

import com.modelgateway.catalog.Provider;
import com.modelgateway.upstream.UpstreamFailure.Kind;
import java.io.IOException;
import java.net.ConnectException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Blocking HTTP to upstreams, one client per provider (own proxy and connect timeout). Callers run
 * on virtual threads, so a blocked send costs no platform thread.
 *
 * <p>Untested assumptions about corporate egress: JDK HttpClient speaks HTTP CONNECT proxies but
 * not SOCKS, and Basic proxy auth over an HTTPS tunnel is disabled by default
 * ({@code jdk.http.auth.tunneling.disabledSchemes}).
 */
public final class UpstreamHttp {

    private static final int MAX_ERROR_DETAIL = 300;

    private final JsonMapper jsonMapper;
    private final Map<String, HttpClient> clients = new ConcurrentHashMap<>();

    public UpstreamHttp(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    /** Sends a JSON POST and returns the parsed 2xx body, or throws a classified failure. */
    public ObjectNode postJson(
            Provider provider, URI uri, Map<String, String> headers, ObjectNode body, Duration timeout) {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(uri)
                        .timeout(timeout)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(jsonMapper.writeValueAsBytes(body)));
        headers.forEach(builder::header);

        HttpResponse<String> response = send(provider, builder.build());
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new UpstreamFailure(
                    UpstreamFailure.classifyStatus(status), status, errorDetail(response.body()));
        }
        try {
            JsonNode parsed = jsonMapper.readTree(response.body());
            if (!(parsed instanceof ObjectNode object)) {
                throw new UpstreamFailure(Kind.MALFORMED_RESPONSE, status, "response body is not a JSON object");
            }
            return object;
        } catch (UpstreamFailure failure) {
            throw failure;
        } catch (RuntimeException exception) {
            throw new UpstreamFailure(Kind.MALFORMED_RESPONSE, status, "response body is not JSON", exception);
        }
    }

    private HttpResponse<String> send(Provider provider, HttpRequest request) {
        try {
            return client(provider).send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpConnectTimeoutException exception) {
            throw new UpstreamFailure(Kind.CONNECT_FAILURE, null, "connect timeout", exception);
        } catch (HttpTimeoutException exception) {
            throw new UpstreamFailure(Kind.READ_TIMEOUT, null, "no response before timeout", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new UpstreamFailure(Kind.TRANSPORT_ERROR, null, "interrupted", exception);
        } catch (IOException exception) {
            if (neverConnected(exception)) {
                throw new UpstreamFailure(Kind.CONNECT_FAILURE, null, "connection failed", exception);
            }
            // The request may already have been sent, so it may have been processed and billed.
            throw new UpstreamFailure(Kind.TRANSPORT_ERROR, null, exception.getClass().getSimpleName(), exception);
        }
    }

    private HttpClient client(Provider provider) {
        return clients.computeIfAbsent(
                provider.name(),
                ignored -> {
                    HttpClient.Builder builder =
                            HttpClient.newBuilder()
                                    .connectTimeout(provider.connectTimeout())
                                    .followRedirects(HttpClient.Redirect.NEVER);
                    provider.proxy().ifPresent(address -> builder.proxy(ProxySelector.of(address)));
                    return builder.build();
                });
    }

    private static boolean neverConnected(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException || cause instanceof UnresolvedAddressException) {
                return true;
            }
        }
        return false;
    }

    /** Upstream error text for the caller and the ledger: the vendor's message, truncated. */
    private String errorDetail(String body) {
        String detail = body == null ? "" : body;
        try {
            JsonNode error = jsonMapper.readTree(detail).path("error");
            if (error.path("message").isString()) {
                detail = error.path("message").asString();
            }
        } catch (RuntimeException ignored) {
            // not JSON; keep the raw text
        }
        return detail.length() > MAX_ERROR_DETAIL ? detail.substring(0, MAX_ERROR_DETAIL) : detail;
    }
}
