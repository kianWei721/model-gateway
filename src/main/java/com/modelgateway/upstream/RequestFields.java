package com.modelgateway.upstream;

import java.util.OptionalInt;
import tools.jackson.databind.JsonNode;

/** Reads OpenAI request fields that more than one component cares about. */
public final class RequestFields {

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
}
