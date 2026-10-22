package com.modelgateway.upstream;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Reads OpenAI request fields that more than one component cares about. */
public final class RequestFields {

    /**
     * Fields a caller may send. Anything else is rejected rather than passed through: a vendor
     * extension like DashScope's {@code enable_search} changes cost and behaviour, so it belongs in
     * the gateway's model configuration, not in each caller's request.
     */
    public static final Set<String> STANDARD =
            Set.of(
                    "model",
                    "messages",
                    "temperature",
                    "top_p",
                    "max_tokens",
                    "max_completion_tokens",
                    "stop",
                    "stream",
                    "stream_options",
                    "tools",
                    "tool_choice",
                    "parallel_tool_calls",
                    "response_format",
                    "seed",
                    "presence_penalty",
                    "frequency_penalty",
                    "logprobs",
                    "top_logprobs",
                    "logit_bias",
                    "user",
                    "n");

    private RequestFields() {}

    /** {@code max_completion_tokens} wins over the older {@code max_tokens}. */
    public static OptionalInt maxOutputTokens(JsonNode request) {
        for (String field : new String[] {"max_completion_tokens", "max_tokens"}) {
            JsonNode value = request.path(field);
            if (value.isIntegralNumber() && value.asInt() > 0) {
                return OptionalInt.of(value.asInt());
            }
        }
        return OptionalInt.empty();
    }

    public static boolean usesTools(JsonNode request) {
        return request.path("tools").isArray() && !request.path("tools").isEmpty();
    }

    public static boolean usesJsonOutput(JsonNode request) {
        String type = request.path("response_format").path("type").asString("");
        return "json_object".equals(type) || "json_schema".equals(type);
    }

    /**
     * Token probabilities or biased sampling. Only OpenAI-protocol upstreams honour these; another
     * protocol would silently drop them and answer something the caller did not ask for.
     */
    public static boolean usesLogitControls(JsonNode request) {
        return request.path("logprobs").asBoolean(false)
                || (request.has("top_logprobs") && !request.path("top_logprobs").isNull())
                || (request.path("logit_bias").isObject() && !request.path("logit_bias").isEmpty());
    }

    public static List<String> nonStandardFields(JsonNode request) {
        List<String> unknown = new ArrayList<>();
        for (String field : request.propertyNames()) {
            if (!STANDARD.contains(field)) {
                unknown.add(field);
            }
        }
        return unknown;
    }
}
