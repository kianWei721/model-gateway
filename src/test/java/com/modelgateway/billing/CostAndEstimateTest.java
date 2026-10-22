package com.modelgateway.billing;

import static org.junit.jupiter.api.Assertions.*;

import com.modelgateway.catalog.Pricing;
import com.modelgateway.upstream.TokenUsage;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CostAndEstimateTest {

    private final Pricing claude =
            new Pricing("USD", new BigDecimal("4.00"), new BigDecimal("0.20"), new BigDecimal("5.00"), new BigDecimal("20.00"));

    @Test
    void costShouldPriceEachBucketSeparatelyAndExactly() {
        TokenUsage usage = new TokenUsage(1_000, 10_000, 2_000, 500, 0);

        // 1000 x 4.00 + 10000 x 0.20 + 2000 x 5.00 + 500 x 20.00 = 26000 per million
        assertEquals(new BigDecimal("0.02600000"), CostCalculator.cost(claude, usage));
    }

    @Test
    void smallCallsShouldNotRoundToZero() {
        assertEquals(new BigDecimal("0.00002400"), CostCalculator.cost(claude, new TokenUsage(1, 0, 0, 1, 0)));
    }

    @Test
    void ceilingShouldAssumeUncachedPromptAndFullOutput() {
        assertEquals(new BigDecimal("0.02400000"), CostCalculator.ceiling(claude, 1_000, 1_000));
    }

    @Test
    void estimatorShouldCountCjkDenserThanAscii() {
        assertEquals(4, TokenEstimator.estimate("增值税表"));
        assertEquals(4, TokenEstimator.estimate("tax filing"), "10 chars / 3, rounded up");

        int estimate =
                TokenEstimator.estimatePromptTokens(
                        JsonMapper.builder().build()
                                .readTree("{\"messages\":[{\"role\":\"user\",\"content\":\"增值税表\"},{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"abc\"}]}]}"));
        assertEquals(4 + 4 + 4 + 1, estimate, "per-message overhead included");
    }
}
