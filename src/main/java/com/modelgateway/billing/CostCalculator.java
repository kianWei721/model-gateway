package com.modelgateway.billing;

import com.modelgateway.catalog.Pricing;
import com.modelgateway.upstream.TokenUsage;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Money is BigDecimal end to end; rounding happens once, at 8 decimals, half-up. */
public final class CostCalculator {

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private CostCalculator() {}

    public static BigDecimal cost(Pricing pricing, TokenUsage usage) {
        BigDecimal total =
                pricing.inputPerMillion().multiply(BigDecimal.valueOf(usage.inputTokens()))
                        .add(pricing.cachedInputPerMillion().multiply(BigDecimal.valueOf(usage.cachedInputTokens())))
                        .add(pricing.cacheWritePerMillion().multiply(BigDecimal.valueOf(usage.cacheWriteTokens())))
                        .add(pricing.outputPerMillion().multiply(BigDecimal.valueOf(usage.outputTokens())));
        return total.divide(MILLION, 8, RoundingMode.HALF_UP);
    }

    /**
     * Worst-case cost of one attempt: the estimated prompt billed uncached plus the full output
     * allowance. Used both to reserve budget and to book attempts whose outcome is unknown.
     */
    public static BigDecimal ceiling(Pricing pricing, int estimatedPromptTokens, int maxOutputTokens) {
        return cost(pricing, new TokenUsage(estimatedPromptTokens, 0, 0, maxOutputTokens, 0));
    }
}
