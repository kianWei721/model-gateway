package com.modelgateway.upstream;

/**
 * Normalized usage. {@code inputTokens} excludes cache reads and writes, so the four priced
 * buckets never overlap. {@code reasoningTokens} is informational: vendors that report it already
 * include it in {@code outputTokens}.
 */
public record TokenUsage(
        int inputTokens,
        int cachedInputTokens,
        int cacheWriteTokens,
        int outputTokens,
        int reasoningTokens) {

    public static final TokenUsage NONE = new TokenUsage(0, 0, 0, 0, 0);

    public TokenUsage {
        if (inputTokens < 0 || cachedInputTokens < 0 || cacheWriteTokens < 0 || outputTokens < 0 || reasoningTokens < 0) {
            throw new IllegalArgumentException("token counts must not be negative");
        }
    }

    public int promptTokens() {
        return inputTokens + cachedInputTokens + cacheWriteTokens;
    }
}
