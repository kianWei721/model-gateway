package com.modelgateway.billing;

import static org.junit.jupiter.api.Assertions.*;

import com.modelgateway.alert.Alert;
import com.modelgateway.alert.AlertService;
import com.modelgateway.billing.BudgetService.Reservation;
import com.modelgateway.core.GatewayException;
import com.modelgateway.ledger.AttemptRecord;
import com.modelgateway.ledger.AttemptRecord.Outcome;
import com.modelgateway.ledger.LedgerRepository;
import com.modelgateway.support.TestDatabase;
import com.modelgateway.upstream.TokenUsage;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class BudgetServiceTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-21T02:00:00Z"), ZoneOffset.UTC);
    private final LedgerRepository ledger = new LedgerRepository(TestDatabase.migrated());
    private final List<Alert> raised = new CopyOnWriteArrayList<>();
    private final AlertService alerts = new AlertService(List.of(raised::add), clock, Duration.ofMinutes(10));

    private BudgetService budgets(String limit) {
        return new BudgetService(
                Map.of("finance", Map.of("CNY", new BigDecimal(limit))),
                ledger,
                alerts,
                List.of(new BigDecimal("0.8"), BigDecimal.ONE),
                clock,
                SHANGHAI);
    }

    @Test
    void concurrentReservationsShouldNeverOvershootTheLimit() throws Exception {
        BudgetService budgets = budgets("1.00");
        int threads = 20;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        budgets.reserve("finance", "CNY", new BigDecimal("0.30"));
                        return true;
                    } catch (GatewayException exceeded) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int granted = 0;
            for (Future<Boolean> result : results) {
                granted += result.get() ? 1 : 0;
            }
            assertEquals(3, granted, "0.30 x 3 fits in 1.00, a fourth would not");
        }
    }

    @Test
    void settlingShouldFreeTheUnusedPartOfTheReservation() {
        BudgetService budgets = budgets("1.00");
        Reservation first = budgets.reserve("finance", "CNY", new BigDecimal("0.90"));
        assertThrows(GatewayException.class, () -> budgets.reserve("finance", "CNY", new BigDecimal("0.20")));

        budgets.settle(first, new BigDecimal("0.05"));

        assertDoesNotThrow(() -> budgets.reserve("finance", "CNY", new BigDecimal("0.20")));
        assertDoesNotThrow(() -> budgets.reserve("other-team", "CNY", new BigDecimal("999")), "no limit configured");
    }

    @Test
    void thresholdAlertsShouldFireOncePerCrossing() {
        BudgetService budgets = budgets("1.00");

        budgets.settle(budgets.reserve("finance", "CNY", new BigDecimal("0.5")), new BigDecimal("0.5"));
        budgets.settle(budgets.reserve("finance", "CNY", new BigDecimal("0.4")), new BigDecimal("0.35"));
        budgets.settle(budgets.reserve("finance", "CNY", new BigDecimal("0.05")), new BigDecimal("0.01"));
        budgets.settle(budgets.reserve("finance", "CNY", new BigDecimal("0.14")), new BigDecimal("0.14"));

        List<String> severities = raised.stream().map(alert -> alert.severity().name()).toList();
        assertEquals(List.of("WARNING", "CRITICAL"), severities, "80% once, then 100% once");
    }

    @Test
    void restartShouldRebuildSpendFromTheLedger() {
        LocalDate today = LocalDate.now(clock.withZone(SHANGHAI));
        ledger.insert(new AttemptRecord("req-1", 1, "ck-1", "finance", "tax", "standard", "dashscope", "qwen-plus",
                "DOMESTIC", "qwen-plus", "MATCH", "abcd1234", Outcome.SUCCESS, null, 200,
                new TokenUsage(1, 0, 0, 1, 0), new BigDecimal("0.95"), false, "CNY", "h", 10, today, clock.instant()));

        BudgetService afterRestart = budgets("1.00");

        assertThrows(GatewayException.class, () -> afterRestart.reserve("finance", "CNY", new BigDecimal("0.10")));
        assertEquals(new BigDecimal("0.95000000"), afterRestart.snapshot("finance", "CNY").get("spent"));
    }
}
