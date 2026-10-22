package com.modelgateway.billing;

import com.modelgateway.alert.Alert.Severity;
import com.modelgateway.alert.AlertService;
import com.modelgateway.core.GatewayException;
import com.modelgateway.ledger.LedgerRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Daily money budgets per team and currency, enforced by reservation.
 *
 * <p>Before an attempt the worst-case cost is reserved; afterwards the reservation is replaced by
 * the actual cost. Checking spend only after the fact would let N concurrent requests overshoot
 * by N times the largest answer. The ledger is the source of truth: counters are rebuilt from it
 * on first use each day, so a restart neither forgets spend nor double counts it. Counters live
 * in this JVM, which assumes a single gateway instance; several instances would need a shared
 * counter (Redis) behind the same interface.
 */
public final class BudgetService {

    public record Reservation(String team, String currency, LocalDate date, BigDecimal amount) {}

    private final Map<String, Map<String, BigDecimal>> limits;
    private final LedgerRepository ledger;
    private final AlertService alerts;
    private final List<BigDecimal> thresholds;
    private final Clock clock;
    private final ZoneId billingZone;
    private final Map<Account, Balance> balances = new ConcurrentHashMap<>();

    public BudgetService(
            Map<String, Map<String, BigDecimal>> limits,
            LedgerRepository ledger,
            AlertService alerts,
            List<BigDecimal> thresholds,
            Clock clock,
            ZoneId billingZone) {
        this.limits = limits;
        this.ledger = ledger;
        this.alerts = alerts;
        this.thresholds = thresholds.stream().sorted().toList();
        this.clock = clock;
        this.billingZone = billingZone;
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(billingZone));
    }

    /** Reserves {@code amount} or rejects the attempt. Teams without a configured limit are unlimited. */
    public Reservation reserve(String team, String currency, BigDecimal amount) {
        LocalDate date = today();
        Optional<BigDecimal> limit = limit(team, currency);
        Balance balance = balance(new Account(team, currency, date));
        synchronized (balance) {
            if (limit.isPresent() && balance.committed().add(amount).compareTo(limit.get()) > 0) {
                throw new GatewayException(
                        429,
                        "BUDGET_EXCEEDED",
                        "Daily " + currency + " budget of team '" + team + "' would be exceeded (limit "
                                + limit.get().toPlainString() + ", committed " + balance.committed().toPlainString() + ")");
            }
            balance.reserved = balance.reserved.add(amount);
        }
        return new Reservation(team, currency, date, amount);
    }

    /** Replaces the reservation with what the attempt actually cost (zero for clean failures). */
    public void settle(Reservation reservation, BigDecimal actualCost) {
        Account account = new Account(reservation.team(), reservation.currency(), reservation.date());
        Balance balance = balance(account);
        BigDecimal before;
        BigDecimal after;
        synchronized (balance) {
            before = balance.spent;
            balance.reserved = balance.reserved.subtract(reservation.amount()).max(BigDecimal.ZERO);
            balance.spent = balance.spent.add(actualCost);
            after = balance.spent;
        }
        limit(account.team(), account.currency()).ifPresent(limit -> alertOnCrossing(account, limit, before, after));
    }

    public Map<String, Object> snapshot(String team, String currency) {
        Balance balance = balance(new Account(team, currency, today()));
        synchronized (balance) {
            return Map.of(
                    "spent", balance.spent,
                    "reserved", balance.reserved,
                    "limit", limit(team, currency).map(BigDecimal::toPlainString).orElse("unlimited"));
        }
    }

    private void alertOnCrossing(Account account, BigDecimal limit, BigDecimal before, BigDecimal after) {
        for (BigDecimal threshold : thresholds) {
            BigDecimal line = limit.multiply(threshold);
            if (before.compareTo(line) < 0 && after.compareTo(line) >= 0) {
                String percent = threshold.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).toPlainString();
                alerts.raise(
                        "budget:" + account + ":" + percent,
                        Duration.ofDays(1),
                        threshold.compareTo(BigDecimal.ONE) >= 0 ? Severity.CRITICAL : Severity.WARNING,
                        "BUDGET_THRESHOLD",
                        "Team " + account.team() + " used " + percent + "% of its daily " + account.currency() + " budget",
                        Map.of(
                                "team", account.team(),
                                "currency", account.currency(),
                                "spent", after.toPlainString(),
                                "limit", limit.toPlainString()));
            }
        }
    }

    private Balance balance(Account account) {
        return balances.computeIfAbsent(
                account, ignored -> new Balance(ledger.spent(account.team(), account.currency(), account.date())));
    }

    private Optional<BigDecimal> limit(String team, String currency) {
        return Optional.ofNullable(limits.getOrDefault(team, Map.of()).get(currency));
    }

    private record Account(String team, String currency, LocalDate date) {}

    private static final class Balance {
        private BigDecimal spent;
        private BigDecimal reserved = BigDecimal.ZERO;

        private Balance(BigDecimal spent) {
            this.spent = spent;
        }

        private BigDecimal committed() {
            return spent.add(reserved);
        }
    }
}
