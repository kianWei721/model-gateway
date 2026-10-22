package com.modelgateway.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/** Scriptable stand-in for vendor endpoints: per-path replies, delays, and captured requests. */
public final class FakeUpstream implements AutoCloseable {

    public record Captured(String path, Map<String, List<String>> headers, String body) {
        public String header(String name) {
            return headers.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .findFirst()
                    .map(entry -> entry.getValue().getFirst())
                    .orElse(null);
        }
    }

    public record Reply(int status, String body, long delayMillis) {
        public static Reply json(int status, String body) {
            return new Reply(status, body, 0);
        }
    }

    @FunctionalInterface
    public interface Responder {
        Reply reply(Captured request);
    }

    private final HttpServer server;
    private final Map<String, Responder> responders = new ConcurrentHashMap<>();
    private final List<Captured> captured = new CopyOnWriteArrayList<>();

    private FakeUpstream(HttpServer server) {
        this.server = server;
    }

    public static FakeUpstream start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            FakeUpstream upstream = new FakeUpstream(server);
            server.createContext("/", upstream::handle);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
            return upstream;
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public String baseUrl(String prefix) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + prefix;
    }

    public void on(String path, Responder responder) {
        responders.put(path, responder);
    }

    public void reset() {
        responders.clear();
        captured.clear();
    }

    public List<Captured> requestsTo(String path) {
        return captured.stream().filter(request -> request.path().equals(path)).toList();
    }

    private void handle(HttpExchange exchange) throws IOException {
        Captured request =
                new Captured(
                        exchange.getRequestURI().getPath(),
                        Map.copyOf(exchange.getRequestHeaders()),
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        captured.add(request);
        Responder responder = responders.get(request.path());
        Reply reply =
                responder == null
                        ? Reply.json(404, "{\"error\":{\"message\":\"no fake for " + request.path() + "\"}}")
                        : responder.reply(request);
        if (reply.delayMillis() > 0) {
            try {
                Thread.sleep(reply.delayMillis());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        try {
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (IOException ignored) {
            // the client gave up (timeout tests)
        } finally {
            exchange.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
