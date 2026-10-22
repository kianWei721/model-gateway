package com.modelgateway.integrity;

import com.modelgateway.alert.Alert.Severity;
import com.modelgateway.alert.AlertService;
import com.modelgateway.ledger.LedgerRepository;
import com.modelgateway.ledger.LedgerRepository.ModelTotals;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Daily per-model reconciliation of gateway metering against the vendor's own usage export.
 *
 * <p>This is the strongest "are we getting what we pay for" check available to a customer: a
 * vendor bill for a model the gateway never called means a leaked key or traffic bypassing the
 * gateway; persistent gaps on a called model mean metering, relay or key-sharing problems.
 */
public final class ReconciliationService {

    public enum Verdict {
        MATCH,
        VENDOR_HIGHER,
        GATEWAY_HIGHER,
        /** Vendor billed a model the gateway did not call that day. */
        NOT_CALLED_BY_GATEWAY,
        /** The gateway called a model the vendor did not bill. */
        NOT_BILLED_BY_VENDOR
    }

    public record VendorLine(String model, long promptTokens, long outputTokens) {}

    public record Line(
            String model,
            long gatewayPromptTokens,
            long vendorPromptTokens,
            long gatewayOutputTokens,
            long vendorOutputTokens,
            int gatewayUnknownAttempts,
            Verdict verdict) {}

    private final LedgerRepository ledger;
    private final AlertService alerts;

    public ReconciliationService(LedgerRepository ledger, AlertService alerts) {
        this.ledger = ledger;
        this.alerts = alerts;
    }

    public List<Line> reconcile(String provider, LocalDate date, List<VendorLine> vendorLines, BigDecimal tolerance) {
        Map<String, ModelTotals> gateway = ledger.billableTotals(provider, date);
        Map<String, VendorLine> vendor = new LinkedHashMap<>();
        vendorLines.forEach(line -> vendor.merge(line.model(), line,
                (a, b) -> new VendorLine(a.model(), a.promptTokens() + b.promptTokens(), a.outputTokens() + b.outputTokens())));

        Set<String> models = new LinkedHashSet<>(gateway.keySet());
        models.addAll(vendor.keySet());
        List<Line> lines = new ArrayList<>();
        for (String model : models) {
            ModelTotals ours = gateway.getOrDefault(model, new ModelTotals(0, 0, 0));
            VendorLine theirs = vendor.getOrDefault(model, new VendorLine(model, 0, 0));
            Verdict verdict = verdict(gateway.containsKey(model), vendor.containsKey(model), ours, theirs, tolerance);
            lines.add(new Line(model, ours.promptTokens(), theirs.promptTokens(), ours.outputTokens(),
                    theirs.outputTokens(), ours.unknownAttempts(), verdict));
            if (verdict != Verdict.MATCH) {
                alerts.raise(
                        "reconcile:" + provider + ":" + date + ":" + model,
                        Duration.ofDays(1),
                        verdict == Verdict.NOT_CALLED_BY_GATEWAY ? Severity.CRITICAL : Severity.WARNING,
                        "RECONCILIATION_" + verdict,
                        provider + "/" + model + " on " + date + ": " + verdict,
                        Map.of("provider", provider, "model", model, "date", date.toString()));
            }
        }
        return lines;
    }

    private static Verdict verdict(
            boolean calledByGateway, boolean billedByVendor, ModelTotals ours, VendorLine theirs, BigDecimal tolerance) {
        if (!calledByGateway) {
            return Verdict.NOT_CALLED_BY_GATEWAY;
        }
        if (!billedByVendor) {
            return Verdict.NOT_BILLED_BY_VENDOR;
        }
        long ourTotal = ours.promptTokens() + ours.outputTokens();
        long theirTotal = theirs.promptTokens() + theirs.outputTokens();
        BigDecimal gap =
                BigDecimal.valueOf(theirTotal - ourTotal)
                        .divide(BigDecimal.valueOf(Math.max(ourTotal, 1)), 6, RoundingMode.HALF_UP);
        if (gap.abs().compareTo(tolerance) <= 0) {
            return Verdict.MATCH;
        }
        return gap.signum() > 0 ? Verdict.VENDOR_HIGHER : Verdict.GATEWAY_HIGHER;
    }
}
