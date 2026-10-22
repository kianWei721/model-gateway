package com.modelgateway.billing;

import tools.jackson.databind.JsonNode;

/**
 * Upper-leaning prompt size estimate without a vendor tokenizer: one token per CJK character,
 * one per three other characters, plus per-message overhead. Only used for reservations and for
 * booking unknown outcomes; settled costs always come from the vendor's reported usage.
 */
public final class TokenEstimator {

    private static final int PER_MESSAGE_OVERHEAD = 4;

    private TokenEstimator() {}

    public static int estimatePromptTokens(JsonNode request) {
        long tokens = 0;
        for (JsonNode message : request.path("messages")) {
            tokens += PER_MESSAGE_OVERHEAD + estimate(text(message.path("content")));
        }
        JsonNode tools = request.path("tools");
        if (tools.isArray()) {
            tokens += estimate(tools.toString());
        }
        return (int) Math.min(Integer.MAX_VALUE, tokens);
    }

    static long estimate(String text) {
        long cjk = 0;
        long other = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            if (script == Character.UnicodeScript.HAN
                    || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA
                    || script == Character.UnicodeScript.HANGUL) {
                cjk++;
            } else {
                other++;
            }
            i += Character.charCount(codePoint);
        }
        return cjk + (other + 2) / 3;
    }

    private static String text(JsonNode content) {
        if (content.isString()) {
            return content.asString();
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode part : content) {
            text.append(part.path("text").asString(""));
        }
        return text.toString();
    }
}
