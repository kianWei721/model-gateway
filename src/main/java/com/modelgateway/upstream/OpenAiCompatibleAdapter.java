package com.modelgateway.upstream;

import com.modelgateway.catalog.Protocol;
import com.modelgateway.catalog.UpstreamModel;
import com.modelgateway.upstream.UpstreamFailure.Kind;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** OpenAI chat.completions and the compatible endpoints of DashScope, DeepSeek and Moonshot. */
public final class OpenAiCompatibleAdapter implements UpstreamAdapter {

    private final UpstreamHttp http;

    public OpenAiCompatibleAdapter(UpstreamHttp http) {
        this.http = http;
    }

    @Override
    public Protocol protocol() {
        return Protocol.OPENAI;
    }

    @Override
    public PreparedCall prepare(UpstreamModel model, ObjectNode openAiRequest) {
        ObjectNode body = openAiRequest.deepCopy();
        body.put("model", model.model());
        body.remove("stream");
        body.remove("stream_options");
        if (!model.forwardSampling()) {
            body.remove("temperature");
            body.remove("top_p");
        }
        // The budget reserves for this many output tokens, so the upstream must be held to it.
        // Without an explicit cap it could answer longer than the reservation covers.
        int maxOutputTokens = RequestFields.maxOutputTokens(openAiRequest).orElse(model.defaultMaxTokens());
        if (RequestFields.maxOutputTokens(openAiRequest).isEmpty()) {
            body.put("max_tokens", maxOutputTokens);
        }
        model.extraBody().forEach((field, value) -> {
            switch (value) {
                case Boolean flag -> body.put(field, flag);
                case BigDecimal number -> body.put(field, number);
                default -> body.put(field, value.toString());
            }
        });
        return new PreparedCall(model, body, maxOutputTokens);
    }

    @Override
    public UpstreamResult execute(PreparedCall call, String apiKey, Duration timeout) {
        UpstreamModel model = call.model();
        ObjectNode response =
                http.postJson(
                        model.provider(),
                        URI.create(model.provider().baseUrl() + "/chat/completions"),
                        Map.of("Authorization", "Bearer " + apiKey),
                        call.body(),
                        timeout);
        if (!response.path("choices").isArray() || !response.path("usage").isObject()) {
            throw new UpstreamFailure(Kind.MALFORMED_RESPONSE, 200, "response lacks choices or usage");
        }
        return new UpstreamResult(
                response,
                response.path("model").asString(null),
                usage(response.path("usage")),
                false);
    }

    /**
     * OpenAI and DashScope report cache hits under {@code prompt_tokens_details.cached_tokens};
     * DeepSeek reports {@code prompt_cache_hit_tokens}. Either way they are part of
     * {@code prompt_tokens}, so they are subtracted from the uncached input.
     */
    static TokenUsage usage(JsonNode usage) {
        int prompt = usage.path("prompt_tokens").asInt(0);
        int cached =
                usage.has("prompt_cache_hit_tokens")
                        ? usage.path("prompt_cache_hit_tokens").asInt(0)
                        : usage.path("prompt_tokens_details").path("cached_tokens").asInt(0);
        cached = Math.min(cached, prompt);
        return new TokenUsage(
                prompt - cached,
                cached,
                0,
                usage.path("completion_tokens").asInt(0),
                usage.path("completion_tokens_details").path("reasoning_tokens").asInt(0));
    }
}
