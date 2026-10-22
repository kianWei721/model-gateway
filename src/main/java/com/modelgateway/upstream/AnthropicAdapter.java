package com.modelgateway.upstream;

import com.modelgateway.catalog.Protocol;
import com.modelgateway.catalog.UpstreamModel;
import com.modelgateway.upstream.UpstreamFailure.Kind;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Anthropic Messages API behind the OpenAI request/response shape.
 *
 * <p>Scope: text conversations. Tool calling, images and tool messages are reported as
 * {@code INCOMPATIBLE} so routing moves on instead of sending a half-translated request.
 */
public final class AnthropicAdapter implements UpstreamAdapter {

    static final String API_VERSION = "2023-06-01";
    static final String SERVER_SIDE_FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private final UpstreamHttp http;
    private final JsonMapper jsonMapper;

    public AnthropicAdapter(UpstreamHttp http, JsonMapper jsonMapper) {
        this.http = http;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public Protocol protocol() {
        return Protocol.ANTHROPIC;
    }

    @Override
    public PreparedCall prepare(UpstreamModel model, ObjectNode request) {
        if (RequestFields.usesTools(request)) {
            throw incompatible("tool calling is not translated for Anthropic upstreams");
        }
        List<String> system = new ArrayList<>();
        List<Turn> turns = new ArrayList<>();
        for (JsonNode message : request.path("messages")) {
            String role = message.path("role").asString("");
            String text = text(message.path("content"));
            switch (role) {
                case "system", "developer" -> system.add(text);
                case "user", "assistant" -> {
                    // The Messages API expects alternating roles; merge consecutive same-role turns.
                    if (!turns.isEmpty() && turns.getLast().role().equals(role)) {
                        turns.set(turns.size() - 1, turns.getLast().append(text));
                    } else {
                        turns.add(new Turn(role, text));
                    }
                }
                default -> throw incompatible("message role '" + role + "' is not translated");
            }
        }
        if (turns.isEmpty() || !"user".equals(turns.getFirst().role())) {
            throw incompatible("conversation must start with a user message");
        }

        int maxTokens = RequestFields.maxOutputTokens(request).orElse(model.defaultMaxTokens());
        ObjectNode body = jsonMapper.createObjectNode();
        body.put("model", model.model());
        body.put("max_tokens", maxTokens);
        if (!system.isEmpty()) {
            body.put("system", String.join("\n\n", system));
        }
        ArrayNode messages = body.putArray("messages");
        turns.forEach(turn -> messages.addObject().put("role", turn.role()).put("content", turn.text()));

        JsonNode stop = request.path("stop");
        if (stop.isString()) {
            body.putArray("stop_sequences").add(stop.asString());
        } else if (stop.isArray() && !stop.isEmpty()) {
            body.set("stop_sequences", stop.deepCopy());
        }
        // Current Claude models reject sampling parameters with a 400, so they are opt-in per model.
        if (model.forwardSampling()) {
            copyIfPresent(request, body, "temperature");
            copyIfPresent(request, body, "top_p");
        }
        if (model.refusalFallback()) {
            body.put("fallbacks", "default");
        }
        return new PreparedCall(model, body, maxTokens);
    }

    @Override
    public UpstreamResult execute(PreparedCall call, String apiKey, Duration timeout) {
        UpstreamModel model = call.model();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-api-key", apiKey);
        headers.put("anthropic-version", API_VERSION);
        if (model.refusalFallback()) {
            headers.put("anthropic-beta", SERVER_SIDE_FALLBACK_BETA);
        }
        ObjectNode response =
                http.postJson(
                        model.provider(),
                        URI.create(model.provider().baseUrl() + "/messages"),
                        headers,
                        call.body(),
                        timeout);
        if (!response.path("content").isArray() || !response.path("usage").isObject()) {
            throw new UpstreamFailure(Kind.MALFORMED_RESPONSE, 200, "response lacks content or usage");
        }
        return new UpstreamResult(
                toChatCompletion(response),
                response.path("model").asString(null),
                usage(response.path("usage")),
                servedByFallback(response.path("usage")));
    }

    private ObjectNode toChatCompletion(ObjectNode response) {
        StringBuilder text = new StringBuilder();
        // Thinking blocks (empty unless display is requested) and fallback markers are not text.
        for (JsonNode block : response.path("content")) {
            if ("text".equals(block.path("type").asString(""))) {
                text.append(block.path("text").asString(""));
            }
        }
        TokenUsage usage = usage(response.path("usage"));

        ObjectNode completion = jsonMapper.createObjectNode();
        completion.put("id", response.path("id").asString(""));
        completion.put("object", "chat.completion");
        completion.put("created", Instant.now().getEpochSecond());
        completion.put("model", response.path("model").asString(""));
        ObjectNode choice = completion.putArray("choices").addObject();
        choice.put("index", 0);
        choice.putObject("message").put("role", "assistant").put("content", text.toString());
        choice.put("finish_reason", finishReason(response.path("stop_reason").asString("")));
        ObjectNode usageNode = completion.putObject("usage");
        usageNode.put("prompt_tokens", usage.promptTokens());
        usageNode.put("completion_tokens", usage.outputTokens());
        usageNode.put("total_tokens", usage.promptTokens() + usage.outputTokens());
        usageNode.putObject("prompt_tokens_details").put("cached_tokens", usage.cachedInputTokens());
        return completion;
    }

    static String finishReason(String stopReason) {
        return switch (stopReason) {
            case "max_tokens", "model_context_window_exceeded" -> "length";
            case "refusal" -> "content_filter";
            case "tool_use" -> "tool_calls";
            default -> "stop";
        };
    }

    /** Anthropic's input_tokens already excludes cache reads and cache writes. */
    static TokenUsage usage(JsonNode usage) {
        return new TokenUsage(
                usage.path("input_tokens").asInt(0),
                usage.path("cache_read_input_tokens").asInt(0),
                usage.path("cache_creation_input_tokens").asInt(0),
                usage.path("output_tokens").asInt(0),
                0);
    }

    /** With server-side fallback, a {@code fallback_message} iteration means another model ran. */
    static boolean servedByFallback(JsonNode usage) {
        for (JsonNode iteration : usage.path("iterations")) {
            if ("fallback_message".equals(iteration.path("type").asString(""))) {
                return true;
            }
        }
        return false;
    }

    private static String text(JsonNode content) {
        if (content.isString()) {
            return content.asString();
        }
        if (!content.isArray()) {
            throw incompatible("message content must be text");
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode part : content) {
            if (!"text".equals(part.path("type").asString(""))) {
                throw incompatible("content part '" + part.path("type").asString("") + "' is not translated");
            }
            text.append(part.path("text").asString(""));
        }
        return text.toString();
    }

    private static void copyIfPresent(JsonNode from, ObjectNode to, String field) {
        if (from.has(field) && !from.get(field).isNull()) {
            to.set(field, from.get(field).deepCopy());
        }
    }

    private static UpstreamFailure incompatible(String message) {
        return new UpstreamFailure(Kind.INCOMPATIBLE, null, message);
    }

    private record Turn(String role, String text) {
        Turn append(String more) {
            return new Turn(role, text + "\n\n" + more);
        }
    }
}
