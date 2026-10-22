package com.modelgateway.ledger;

import com.modelgateway.upstream.TokenUsage;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One upstream attempt. {@code model} is the configured model and the pricing basis;
 * {@code servedModel} is what the upstream claimed and is only compared against it.
 * {@code correlationId} is the caller's trace id, when it sent one.
 */
public record AttemptRecord(
        String requestId,
        String correlationId,
        int attemptNo,
        String clientKeyId,
        String team,
        String project,
        String route,
        String provider,
        String model,
        String region,
        String servedModel,
        String modelCheck,
        String providerKeyFingerprint,
        Outcome outcome,
        String failureKind,
        Integer httpStatus,
        TokenUsage usage,
        BigDecimal cost,
        boolean costEstimated,
        String currency,
        String requestHash,
        long latencyMs,
        LocalDate usageDate,
        Instant createdAt) {

    public enum Outcome {
        SUCCESS,
        FAILED,
        /** The upstream may have processed and billed the call; cost is an estimate. */
        UNKNOWN
    }
}
