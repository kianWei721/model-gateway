package com.modelgateway.api;

import com.modelgateway.alert.Alert;
import com.modelgateway.alert.AlertService;
import com.modelgateway.auth.ClientKeyService;
import com.modelgateway.auth.ClientKeyService.IssuedKey;
import com.modelgateway.billing.BudgetService;
import com.modelgateway.catalog.Catalog;
import com.modelgateway.integrity.ReconciliationService;
import com.modelgateway.integrity.ReconciliationService.Line;
import com.modelgateway.integrity.ReconciliationService.VendorLine;
import com.modelgateway.ledger.LedgerRepository;
import com.modelgateway.providerkeys.ProviderKey;
import com.modelgateway.providerkeys.ProviderKeyPool;
import com.modelgateway.resilience.UpstreamGuards;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Operator API. Secrets go in, never out: responses carry ids and fingerprints only. */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final ClientKeyService clientKeys;
    private final ProviderKeyPool providerKeys;
    private final Catalog catalog;
    private final LedgerRepository ledger;
    private final BudgetService budgets;
    private final UpstreamGuards guards;
    private final AlertService alerts;
    private final ReconciliationService reconciliation;

    public AdminController(
            ClientKeyService clientKeys,
            ProviderKeyPool providerKeys,
            Catalog catalog,
            LedgerRepository ledger,
            BudgetService budgets,
            UpstreamGuards guards,
            AlertService alerts,
            ReconciliationService reconciliation) {
        this.clientKeys = clientKeys;
        this.providerKeys = providerKeys;
        this.catalog = catalog;
        this.ledger = ledger;
        this.budgets = budgets;
        this.guards = guards;
        this.alerts = alerts;
        this.reconciliation = reconciliation;
    }

    /** Omitted {@code allowOverseas} means false: data leaves the country only on explicit request. */
    public record IssueClientKey(String team, String project, Set<String> routes, Boolean allowOverseas, Integer rpm) {}

    @PostMapping("/client-keys")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> issue(@RequestBody IssueClientKey request) {
        IssuedKey issued =
                clientKeys.issue(request.team(), request.project(), request.routes(),
                        Boolean.TRUE.equals(request.allowOverseas()), request.rpm() == null ? 60 : request.rpm());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", issued.key().id());
        body.put("key", issued.plaintext());
        body.put("note", "shown once; only a hash is stored");
        body.put("team", issued.key().team());
        body.put("project", issued.key().project());
        body.put("routes", issued.key().allowedRoutes());
        body.put("allowOverseas", issued.key().allowOverseas());
        body.put("rpm", issued.key().rpm());
        return body;
    }

    @DeleteMapping("/client-keys/{id}")
    public Map<String, Object> revoke(@PathVariable String id) {
        return Map.of("id", id, "revoked", clientKeys.revoke(id));
    }

    public record AddProviderKey(String provider, String secret) {}

    @PostMapping("/provider-keys")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> addProviderKey(@RequestBody AddProviderKey request) {
        if (request.provider() == null || !catalog.providers().containsKey(request.provider())) {
            throw new IllegalArgumentException("unknown provider " + request.provider());
        }
        if (request.secret() == null || request.secret().isBlank()) {
            throw new IllegalArgumentException("secret is required");
        }
        ProviderKey key = providerKeys.add(request.provider(), request.secret().trim());
        return Map.of(
                "id", key.id(),
                "provider", key.provider(),
                "fingerprint", key.fingerprint(),
                "masterKeyVersion", key.masterKeyVersion());
    }

    @PostMapping("/provider-keys/{id}/disable")
    public Map<String, Object> disableProviderKey(@PathVariable String id) {
        return Map.of("id", id, "disabled", providerKeys.disable(id));
    }

    /** Step two of master-key rotation: re-seal everything under the new current version. */
    @PostMapping("/provider-keys/reseal")
    public Map<String, Object> reseal() {
        return Map.of("resealed", providerKeys.resealAll());
    }

    @GetMapping("/usage")
    public List<Map<String, Object>> usage(
            @RequestParam(defaultValue = "team") String groupBy,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        LocalDate today = budgets.today();
        return ledger.usage(groupBy, from == null ? today : from, to == null ? today : to);
    }

    @GetMapping("/requests/{requestId}")
    public List<Map<String, Object>> request(@PathVariable String requestId) {
        return ledger.attempts(requestId);
    }

    /** Reverse lookup from a caller's trace id, e.g. an AgentForge retrieval, to every attempt. */
    @GetMapping("/requests")
    public List<Map<String, Object>> requestsByCorrelation(@RequestParam String correlationId) {
        return ledger.attemptsByCorrelation(correlationId);
    }

    @GetMapping("/budgets/{team}")
    public Map<String, Object> budget(@PathVariable String team, @RequestParam(defaultValue = "CNY") String currency) {
        return budgets.snapshot(team, currency.toUpperCase());
    }

    @GetMapping("/upstreams")
    public Map<String, Object> upstreams() {
        return Map.of("models", guards.snapshot(), "activeProviderKeys", providerKeys.activeCounts());
    }

    @PostMapping("/upstreams/{provider}/{model}/reset")
    public Map<String, Object> resetBreaker(@PathVariable String provider, @PathVariable String model) {
        return Map.of("reset", guards.reset(provider + "/" + model));
    }

    @GetMapping("/alerts")
    public List<Alert> alerts() {
        return alerts.recent();
    }

    public record ReconcileRequest(String provider, LocalDate date, BigDecimal tolerance, List<VendorLine> lines) {}

    @PostMapping("/reconciliation")
    public List<Line> reconcile(@RequestBody ReconcileRequest request) {
        if (request.provider() == null || request.date() == null || request.lines() == null) {
            throw new IllegalArgumentException("provider, date and lines are required");
        }
        return reconciliation.reconcile(
                request.provider(),
                request.date(),
                request.lines(),
                request.tolerance() == null ? new BigDecimal("0.02") : request.tolerance());
    }
}
