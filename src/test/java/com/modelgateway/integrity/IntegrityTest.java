package com.modelgateway.integrity;

import static org.junit.jupiter.api.Assertions.*;

import com.modelgateway.alert.AlertService;
import com.modelgateway.catalog.Catalog;
import com.modelgateway.catalog.UpstreamModel;
import com.modelgateway.integrity.ReconciliationService.Line;
import com.modelgateway.integrity.ReconciliationService.Verdict;
import com.modelgateway.integrity.ReconciliationService.VendorLine;
import com.modelgateway.ledger.AttemptRecord;
import com.modelgateway.ledger.AttemptRecord.Outcome;
import com.modelgateway.ledger.LedgerRepository;
import com.modelgateway.support.TestConfigs;
import com.modelgateway.support.TestDatabase;
import com.modelgateway.upstream.TokenUsage;
import com.modelgateway.upstream.UpstreamResult;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class IntegrityTest {

    private final Catalog catalog = new Catalog(TestConfigs.standard("http://127.0.0.1:1"));

    @Test
    void datedSnapshotsShouldMatchButSiblingModelsShouldNot() {
        UpstreamModel qwen = catalog.model("dashscope/qwen-plus").orElseThrow();

        assertEquals(ModelCheck.MATCH, ModelCheck.of(qwen, served("qwen-plus")));
        assertEquals(ModelCheck.MATCH, ModelCheck.of(qwen, served("qwen-plus-2026-07-28")));
        assertEquals(ModelCheck.MISMATCH, ModelCheck.of(qwen, served("qwen-plus-mini")));
        assertEquals(ModelCheck.MISMATCH, ModelCheck.of(qwen, served("qwen-turbo")));
        assertEquals(ModelCheck.ABSENT, ModelCheck.of(qwen, served(null)));
        assertEquals(
                ModelCheck.UPSTREAM_FALLBACK,
                ModelCheck.of(qwen, new UpstreamResult(null, "qwen-plus", TokenUsage.NONE, true)));
    }

    @Test
    void reconciliationShouldClassifyEveryKindOfGap() {
        LedgerRepository ledger = new LedgerRepository(TestDatabase.migrated());
        LocalDate day = LocalDate.parse("2026-10-20");
        meter(ledger, day, "deepseek-chat", Outcome.SUCCESS, 1_000, 200);
        meter(ledger, day, "deepseek-reasoner", Outcome.SUCCESS, 1_000, 200);
        meter(ledger, day, "deepseek-reasoner", Outcome.UNKNOWN, 500, 100);
        meter(ledger, day, "deepseek-v4", Outcome.SUCCESS, 100, 10);
        meter(ledger, day, "deepseek-chat", Outcome.FAILED, 0, 0);
        ReconciliationService service =
                new ReconciliationService(ledger, new AlertService(List.of(), Clock.systemUTC(), Duration.ofMinutes(10)));

        Map<String, Line> lines =
                service.reconcile(
                                "deepseek",
                                day,
                                List.of(
                                        new VendorLine("deepseek-chat", 1_010, 200),
                                        new VendorLine("deepseek-reasoner", 1_900, 400),
                                        new VendorLine("deepseek-coder", 9_000, 900)),
                                new BigDecimal("0.02"))
                        .stream()
                        .collect(Collectors.toMap(Line::model, line -> line));

        assertEquals(Verdict.MATCH, lines.get("deepseek-chat").verdict(), "within 2%");
        assertEquals(Verdict.VENDOR_HIGHER, lines.get("deepseek-reasoner").verdict());
        assertEquals(1, lines.get("deepseek-reasoner").gatewayUnknownAttempts(), "an unknown attempt may explain part of it");
        assertEquals(Verdict.NOT_CALLED_BY_GATEWAY, lines.get("deepseek-coder").verdict());
        assertEquals(Verdict.NOT_BILLED_BY_VENDOR, lines.get("deepseek-v4").verdict());
    }

    private static void meter(LedgerRepository ledger, LocalDate day, String model, Outcome outcome, int prompt, int output) {
        ledger.insert(new AttemptRecord("req-" + System.nanoTime(), 1, "ck", "finance", "tax", "standard", "deepseek", model,
                "DOMESTIC", model, "MATCH", "fp", outcome, null, 200, new TokenUsage(prompt, 0, 0, output, 0),
                BigDecimal.ZERO, outcome == Outcome.UNKNOWN, "CNY", "h", 1, day, Instant.now()));
    }

    private static UpstreamResult served(String model) {
        return new UpstreamResult(null, model, TokenUsage.NONE, false);
    }
}
