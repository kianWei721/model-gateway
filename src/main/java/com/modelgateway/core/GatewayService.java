package com.modelgateway.core;

import com.modelgateway.alert.Alert.Severity;
import com.modelgateway.alert.AlertService;
import com.modelgateway.auth.ClientKey;
import com.modelgateway.billing.BudgetService;
import com.modelgateway.billing.BudgetService.Reservation;
import com.modelgateway.billing.CostCalculator;
import com.modelgateway.billing.RpmLimiter;
import com.modelgateway.billing.TokenEstimator;
import com.modelgateway.catalog.Protocol;
import com.modelgateway.catalog.UpstreamModel;
import com.modelgateway.integrity.ModelCheck;
import com.modelgateway.ledger.AttemptRecord;
import com.modelgateway.ledger.AttemptRecord.Outcome;
import com.modelgateway.ledger.LedgerRepository;
import com.modelgateway.providerkeys.ProviderKeyPool;
import com.modelgateway.providerkeys.ProviderKeyPool.LeasedKey;
import com.modelgateway.resilience.UpstreamGuards;
import com.modelgateway.routing.RoutePlanner;
import com.modelgateway.routing.RoutePlanner.RequestTraits;
import com.modelgateway.upstream.PreparedCall;
import com.modelgateway.upstream.RequestFields;
import com.modelgateway.upstream.TokenUsage;
import com.modelgateway.upstream.UpstreamAdapter;
import com.modelgateway.upstream.UpstreamFailure;
import com.modelgateway.upstream.UpstreamFailure.Kind;
import com.modelgateway.upstream.UpstreamResult;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * One chat completion through the gateway.
 *
 * <p>Order of business: rate limit, plan the route, then walk candidates in declared order. Per
 * candidate: translate, check the breaker and concurrency cap, lease a key, reserve budget, call,
 * meter. Every attempt that reached the network is written to the ledger before the caller gets
 * an answer, failed ones included. The response always says which upstream served it.
 */
public final class GatewayService {

    /** Below this, a new attempt would only burn money and still miss the caller's deadline. */
    private static final Duration MIN_ATTEMPT_TIME = Duration.ofSeconds(1);

    private final RoutePlanner planner;
    private final Map<Protocol, UpstreamAdapter> adapters;
    private final UpstreamGuards guards;
    private final ProviderKeyPool keys;
    private final BudgetService budgets;
    private final RpmLimiter rpmLimiter;
    private final LedgerRepository ledger;
    private final AlertService alerts;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final Duration deadline;
    private final Duration attemptTimeout;
    private final int maxAttempts;

    public GatewayService(
            RoutePlanner planner,
            List<UpstreamAdapter> adapters,
            UpstreamGuards guards,
            ProviderKeyPool keys,
            BudgetService budgets,
            RpmLimiter rpmLimiter,
            LedgerRepository ledger,
            AlertService alerts,
            JsonMapper jsonMapper,
            Clock clock,
            Duration deadline,
            Duration attemptTimeout,
            int maxAttempts) {
        this.planner = planner;
        Map<Protocol, UpstreamAdapter> byProtocol = new LinkedHashMap<>();
        adapters.forEach(adapter -> byProtocol.put(adapter.protocol(), adapter));
        this.adapters = Map.copyOf(byProtocol);
        this.guards = guards;
        this.keys = keys;
        this.budgets = budgets;
        this.rpmLimiter = rpmLimiter;
        this.ledger = ledger;
        this.alerts = alerts;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.deadline = deadline;
        this.attemptTimeout = attemptTimeout;
        this.maxAttempts = maxAttempts;
    }

    /**
     * @param callerDeadlineMs how long the caller will still wait ({@code X-Gateway-Deadline-Ms});
     *     the gateway never keeps trying, and paying, after the caller has given up
     * @param correlationId the caller's own trace id ({@code X-Correlation-Id}), stored with every
     *     attempt so either side can be traced from the other
     */
    public GatewayResult complete(ClientKey key, ObjectNode request, String callerDeadlineMs, String correlationId) {
        Instant startedAt = clock.instant();
        String correlation = validCorrelationId(correlationId) ? correlationId : null;
        Exchange exchange =
                new Exchange("req-" + UUID.randomUUID(), key, sha256(jsonMapper.writeValueAsBytes(request)), correlation);
        try {
            if (correlationId != null && correlation == null) {
                throw new GatewayException(400, "INVALID_CORRELATION_ID",
                        "X-Correlation-Id must be 1-128 characters of letters, digits, '.', '_', ':' or '-'");
            }
            return run(exchange, request, startedAt.plus(effectiveDeadline(callerDeadlineMs)));
        } catch (GatewayException failure) {
            // Every answer, error or not, names the request so the caller can quote it to us.
            throw failure.withHeaders(exchange.identityHeaders());
        }
    }

    private GatewayResult run(Exchange exchange, ObjectNode request, Instant deadlineAt) {
        ClientKey key = exchange.key;
        if (!rpmLimiter.tryAcquire(key.id(), key.rpm())) {
            throw new GatewayException(429, "RATE_LIMITED", "Key exceeded " + key.rpm() + " requests per minute",
                    Map.of("Retry-After", "1"));
        }
        validate(request);
        String route = request.path("model").asString("");
        List<UpstreamModel> candidates =
                planner.plan(route, key, new RequestTraits(RequestFields.usesTools(request), RequestFields.usesJsonOutput(request)));
        int estimatedPrompt = TokenEstimator.estimatePromptTokens(request);

        for (int index = 0; index < candidates.size(); index++) {
            UpstreamModel model = candidates.get(index);
            if (exchange.attempts >= maxAttempts) {
                exchange.trail.add(model.id() + ":ATTEMPTS_EXHAUSTED");
                break;
            }
            PreparedCall call;
            try {
                call = adapters.get(model.provider().protocol()).prepare(model, request);
            } catch (UpstreamFailure incompatible) {
                exchange.trail.add(model.id() + ":INCOMPATIBLE");
                continue;
            }
            if (!guards.tryEnter(model)) {
                exchange.trail.add(model.id() + ":SATURATED");
                continue;
            }
            try {
                GatewayResult result = tryCandidate(exchange, route, model, index, call, estimatedPrompt, deadlineAt);
                if (result != null) {
                    return result;
                }
            } finally {
                guards.exit(model);
            }
        }
        throw new GatewayException(
                503,
                "NO_UPSTREAM_AVAILABLE",
                "No upstream of route '" + route + "' could serve the request",
                exchange.headers(route, null, null));
    }

    private static void validate(ObjectNode request) {
        if (!request.path("model").isString() || !request.path("messages").isArray() || request.path("messages").isEmpty()) {
            throw new GatewayException(400, "INVALID_REQUEST", "model (a route name) and a non-empty messages array are required");
        }
        List<String> nonStandard = RequestFields.nonStandardFields(request);
        if (!nonStandard.isEmpty()) {
            throw new GatewayException(400, "UNSUPPORTED_FIELD",
                    "Non-standard request fields are not accepted: " + String.join(", ", nonStandard)
                            + ". Vendor-specific switches are configured per model in the gateway.");
        }
        if (request.has("n") && !(request.path("n").isIntegralNumber() && request.path("n").asInt() == 1)) {
            throw new GatewayException(400, "UNSUPPORTED_FIELD", "n must be 1: budgets are reserved for a single completion");
        }
        if (request.path("stream").asBoolean(false)) {
            throw new GatewayException(400, "STREAMING_UNSUPPORTED", "stream=true is not supported by this gateway yet");
        }
    }

    private Duration effectiveDeadline(String callerDeadlineMs) {
        if (callerDeadlineMs == null || callerDeadlineMs.isBlank()) {
            return deadline;
        }
        long millis;
        try {
            millis = Long.parseLong(callerDeadlineMs.trim());
        } catch (NumberFormatException exception) {
            millis = -1;
        }
        if (millis <= 0 || millis > Duration.ofMinutes(10).toMillis()) {
            throw new GatewayException(400, "INVALID_DEADLINE", "X-Gateway-Deadline-Ms must be between 1 and 600000");
        }
        Duration caller = Duration.ofMillis(millis);
        return caller.compareTo(deadline) < 0 ? caller : deadline;
    }

    private static boolean validCorrelationId(String value) {
        return value != null && value.matches("[A-Za-z0-9._:-]{1,128}");
    }

    /** Returns the result, or null to move on to the next candidate. Throws to end the request. */
    private GatewayResult tryCandidate(
            Exchange exchange,
            String route,
            UpstreamModel model,
            int candidateIndex,
            PreparedCall call,
            int estimatedPrompt,
            Instant deadlineAt) {
        Set<String> rejectedKeys = new HashSet<>();
        while (exchange.attempts < maxAttempts) {
            CircuitBreaker breaker = guards.breaker(model);
            if (!breaker.tryAcquirePermission()) {
                exchange.trail.add(model.id() + ":CIRCUIT_OPEN");
                return null;
            }
            LeasedKey leased = keys.next(model.provider().name(), rejectedKeys).orElse(null);
            if (leased == null) {
                breaker.releasePermission();
                exchange.trail.add(model.id() + ":NO_KEY");
                alerts.raise("no-key:" + model.provider().name(), Severity.CRITICAL, "NO_PROVIDER_KEY",
                        "No usable key for provider " + model.provider().name(), Map.of("provider", model.provider().name()));
                return null;
            }
            Duration remaining = Duration.between(clock.instant(), deadlineAt);
            if (remaining.compareTo(MIN_ATTEMPT_TIME) < 0) {
                breaker.releasePermission();
                throw new GatewayException(504, "DEADLINE_EXCEEDED", "Gateway deadline reached before an upstream answered",
                        exchange.headers(route, null, null));
            }

            BigDecimal ceiling = CostCalculator.ceiling(model.pricing(), estimatedPrompt, call.maxOutputTokens());
            Reservation reservation;
            try {
                reservation = budgets.reserve(exchange.key.team(), model.pricing().currency(), ceiling);
            } catch (GatewayException budgetExceeded) {
                breaker.releasePermission();
                throw budgetExceeded;
            }

            int attemptNo = ++exchange.attempts;
            long started = System.nanoTime();
            Duration timeout = remaining.compareTo(attemptTimeout) < 0 ? remaining : attemptTimeout;
            try {
                UpstreamResult result = adapters.get(model.provider().protocol()).execute(call, leased.secret(), timeout);
                long elapsed = System.nanoTime() - started;
                breaker.onSuccess(elapsed, TimeUnit.NANOSECONDS);

                ModelCheck check = ModelCheck.of(model, result);
                if (check == ModelCheck.MISMATCH || check == ModelCheck.UPSTREAM_FALLBACK) {
                    alerts.raise("model-check:" + model.id() + ":" + check, Severity.WARNING, "MODEL_" + check,
                            "Upstream " + model.id() + " reported model '" + result.servedModel() + "'",
                            Map.of("expected", model.model(), "served", String.valueOf(result.servedModel())));
                }
                BigDecimal cost = CostCalculator.cost(model.pricing(), result.usage());
                record(exchange, route, model, attemptNo, leased, Outcome.SUCCESS, null, 200, result.servedModel(),
                        check.name(), result.usage(), cost, false, elapsed);
                budgets.settle(reservation, cost);
                exchange.trail.add(model.id() + ":OK");

                ObjectNode body = result.response();
                Map<String, String> headers = exchange.headers(route, model, result.servedModel());
                headers.put("X-Gateway-Fallback", String.valueOf(candidateIndex > 0));
                headers.put("X-Gateway-Model-Check", check.name());
                return new GatewayResult(body, headers);
            } catch (UpstreamFailure failure) {
                long elapsed = System.nanoTime() - started;
                breaker.onError(elapsed, TimeUnit.NANOSECONDS, failure);
                exchange.trail.add(model.id() + ":" + failure.kind());

                if (failure.kind().billingUnknown()) {
                    // The vendor may have billed this call: book the ceiling and stop. Retrying
                    // elsewhere could pay twice for one answer.
                    TokenUsage estimated = new TokenUsage(estimatedPrompt, 0, 0, call.maxOutputTokens(), 0);
                    record(exchange, route, model, attemptNo, leased, Outcome.UNKNOWN, failure.kind().name(),
                            failure.httpStatus(), null, null, estimated, ceiling, true, elapsed);
                    budgets.settle(reservation, ceiling);
                    alerts.raise("unknown:" + model.id(), Severity.WARNING, "UPSTREAM_OUTCOME_UNKNOWN",
                            "Upstream " + model.id() + " gave no usable answer (" + failure.kind() + "); booked at estimate",
                            Map.of("model", model.id(), "request", exchange.requestId));
                    throw new GatewayException(
                            failure.kind() == Kind.READ_TIMEOUT ? 504 : 502,
                            "UPSTREAM_OUTCOME_UNKNOWN",
                            "Upstream " + model.id() + " did not return a usable answer; it may still have been billed",
                            exchange.headers(route, null, null));
                }

                record(exchange, route, model, attemptNo, leased, Outcome.FAILED, failure.kind().name(),
                        failure.httpStatus(), null, null, TokenUsage.NONE, BigDecimal.ZERO, false, elapsed);
                budgets.settle(reservation, BigDecimal.ZERO);

                switch (failure.kind()) {
                    case UNAUTHORIZED -> {
                        keys.coolDown(leased);
                        rejectedKeys.add(leased.id());
                        alerts.raise("key-401:" + leased.id(), Severity.CRITICAL, "PROVIDER_KEY_UNAUTHORIZED",
                                "Provider rejected key " + leased.fingerprint() + " of " + leased.provider(),
                                Map.of("provider", leased.provider(), "fingerprint", leased.fingerprint()));
                        // same candidate, next key
                    }
                    case UPSTREAM_REJECTED -> {
                        alerts.raise("rejected:" + model.id(), Severity.CRITICAL, "UPSTREAM_REJECTED",
                                "Upstream " + model.id() + " refused our account (HTTP " + failure.httpStatus()
                                        + "): region, billing or entitlement",
                                Map.of("model", model.id(), "detail", failure.getMessage()));
                        return null;
                    }
                    case BAD_REQUEST -> throw new GatewayException(
                            400, "UPSTREAM_BAD_REQUEST", failure.getMessage(), exchange.headers(route, null, null));
                    default -> {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private void record(
            Exchange exchange,
            String route,
            UpstreamModel model,
            int attemptNo,
            LeasedKey key,
            Outcome outcome,
            String failureKind,
            Integer httpStatus,
            String servedModel,
            String modelCheck,
            TokenUsage usage,
            BigDecimal cost,
            boolean estimated,
            long elapsedNanos) {
        ledger.insert(
                new AttemptRecord(
                        exchange.requestId,
                        exchange.correlationId,
                        attemptNo,
                        exchange.key.id(),
                        exchange.key.team(),
                        exchange.key.project(),
                        route,
                        model.provider().name(),
                        model.model(),
                        model.region().name(),
                        servedModel,
                        modelCheck,
                        key.fingerprint(),
                        outcome,
                        failureKind,
                        httpStatus,
                        usage,
                        cost,
                        estimated,
                        model.pricing().currency(),
                        exchange.requestHash,
                        TimeUnit.NANOSECONDS.toMillis(elapsedNanos),
                        budgets.today(),
                        clock.instant()));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** Mutable per-request state; confined to the request's thread. */
    private static final class Exchange {
        private final String requestId;
        private final ClientKey key;
        private final String requestHash;
        private final String correlationId;
        private final List<String> trail = new ArrayList<>();
        private int attempts;

        private Exchange(String requestId, ClientKey key, String requestHash, String correlationId) {
            this.requestId = requestId;
            this.key = key;
            this.requestHash = requestHash;
            this.correlationId = correlationId;
        }

        private Map<String, String> identityHeaders() {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("X-Gateway-Request-Id", requestId);
            if (correlationId != null) {
                headers.put("X-Correlation-Id", correlationId);
            }
            return headers;
        }

        private Map<String, String> headers(String route, UpstreamModel served, String servedModel) {
            Map<String, String> headers = identityHeaders();
            headers.put("X-Gateway-Route", route);
            headers.put("X-Gateway-Attempts", String.valueOf(attempts));
            headers.put("X-Gateway-Trail", String.join(";", trail));
            if (served != null) {
                headers.put("X-Gateway-Served-Provider", served.provider().name());
                headers.put("X-Gateway-Served-Model", servedModel == null ? served.model() : servedModel);
                headers.put("X-Gateway-Region", served.region().name());
            }
            return headers;
        }
    }
}
