package com.modelgateway.catalog;

import java.math.BigDecimal;

/** Per-million-token prices in one currency. The gateway never converts currencies. */
public record Pricing(
        String currency,
        BigDecimal inputPerMillion,
        BigDecimal cachedInputPerMillion,
        BigDecimal cacheWritePerMillion,
        BigDecimal outputPerMillion) {}
