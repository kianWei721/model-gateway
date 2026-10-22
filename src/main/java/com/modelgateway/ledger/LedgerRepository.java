package com.modelgateway.ledger;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

public class LedgerRepository {

    /** Whitelisted report dimensions; the SQL never contains caller-supplied text. */
    private static final Map<String, String> DIMENSIONS =
            Map.of(
                    "team", "team",
                    "project", "team, project",
                    "model", "provider, model",
                    "route", "route");

    private final JdbcTemplate jdbcTemplate;

    public LedgerRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(AttemptRecord record) {
        jdbcTemplate.update(
                """
                INSERT INTO gw_call_attempt (
                    request_id, correlation_id, attempt_no, client_key_id, team, project, route, provider, model, region,
                    served_model, model_check, provider_key_fingerprint, outcome, failure_kind, http_status,
                    input_tokens, cached_input_tokens, cache_write_tokens, output_tokens, reasoning_tokens,
                    cost, cost_estimated, currency, request_hash, latency_ms, usage_date, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                record.requestId(),
                record.correlationId(),
                record.attemptNo(),
                record.clientKeyId(),
                record.team(),
                record.project(),
                record.route(),
                record.provider(),
                record.model(),
                record.region(),
                record.servedModel(),
                record.modelCheck(),
                record.providerKeyFingerprint(),
                record.outcome().name(),
                record.failureKind(),
                record.httpStatus(),
                record.usage().inputTokens(),
                record.usage().cachedInputTokens(),
                record.usage().cacheWriteTokens(),
                record.usage().outputTokens(),
                record.usage().reasoningTokens(),
                record.cost(),
                record.costEstimated(),
                record.currency(),
                record.requestHash(),
                record.latencyMs(),
                Date.valueOf(record.usageDate()),
                Timestamp.from(record.createdAt()));
    }

    /** Spend that counts against a budget: settled costs plus estimates for unknown outcomes. */
    public BigDecimal spent(String team, String currency, LocalDate date) {
        BigDecimal spent =
                jdbcTemplate.queryForObject(
                        "SELECT SUM(cost) FROM gw_call_attempt WHERE team = ? AND currency = ? AND usage_date = ?",
                        BigDecimal.class,
                        team,
                        currency,
                        Date.valueOf(date));
        return spent == null ? BigDecimal.ZERO : spent;
    }

    public List<Map<String, Object>> usage(String groupBy, LocalDate from, LocalDate to) {
        String columns = DIMENSIONS.get(groupBy);
        if (columns == null) {
            throw new IllegalArgumentException("group-by must be one of " + DIMENSIONS.keySet());
        }
        return jdbcTemplate.queryForList(
                "SELECT "
                        + columns
                        + """
                        , currency,
                          COUNT(DISTINCT request_id) AS requests,
                          SUM(CASE WHEN outcome = 'SUCCESS' THEN 1 ELSE 0 END) AS succeeded,
                          SUM(CASE WHEN outcome = 'FAILED' THEN 1 ELSE 0 END) AS failed,
                          SUM(CASE WHEN outcome = 'UNKNOWN' THEN 1 ELSE 0 END) AS unknown_outcome,
                          SUM(input_tokens + cached_input_tokens + cache_write_tokens) AS prompt_tokens,
                          SUM(output_tokens) AS output_tokens,
                          SUM(cost) AS cost
                        FROM gw_call_attempt
                        WHERE usage_date BETWEEN ? AND ?
                        GROUP BY """
                        + " "
                        + columns
                        + ", currency ORDER BY "
                        + columns,
                Date.valueOf(from),
                Date.valueOf(to));
    }

    /**
     * Per-model totals the vendor should also have billed: successes plus unknown outcomes, the
     * latter counted separately because their tokens are estimates.
     */
    public Map<String, ModelTotals> billableTotals(String provider, LocalDate date) {
        Map<String, ModelTotals> totals = new LinkedHashMap<>();
        jdbcTemplate.query(
                """
                SELECT model,
                       SUM(CASE WHEN outcome = 'SUCCESS' THEN input_tokens + cached_input_tokens + cache_write_tokens ELSE 0 END) AS prompt_tokens,
                       SUM(CASE WHEN outcome = 'SUCCESS' THEN output_tokens ELSE 0 END) AS output_tokens,
                       SUM(CASE WHEN outcome = 'UNKNOWN' THEN 1 ELSE 0 END) AS unknown_attempts
                FROM gw_call_attempt
                WHERE provider = ? AND usage_date = ? AND outcome IN ('SUCCESS', 'UNKNOWN')
                GROUP BY model
                """,
                rs -> {
                    totals.put(
                            rs.getString("model"),
                            new ModelTotals(rs.getLong("prompt_tokens"), rs.getLong("output_tokens"), rs.getInt("unknown_attempts")));
                },
                provider,
                Date.valueOf(date));
        return totals;
    }

    public List<Map<String, Object>> attempts(String requestId) {
        return new ArrayList<>(
                jdbcTemplate.queryForList(
                        """
                        SELECT attempt_no, correlation_id, provider, model, served_model, model_check, provider_key_fingerprint,
                               outcome, failure_kind, http_status, cost, cost_estimated, currency, latency_ms
                        FROM gw_call_attempt WHERE request_id = ? ORDER BY attempt_no
                        """,
                        requestId));
    }

    /** Everything one caller-side operation caused, across all its gateway requests. */
    public List<Map<String, Object>> attemptsByCorrelation(String correlationId) {
        return new ArrayList<>(
                jdbcTemplate.queryForList(
                        """
                        SELECT request_id, attempt_no, route, provider, model, served_model, model_check,
                               outcome, failure_kind, http_status, cost, cost_estimated, currency, latency_ms, created_at
                        FROM gw_call_attempt WHERE correlation_id = ? ORDER BY created_at, request_id, attempt_no
                        """,
                        correlationId));
    }

    public record ModelTotals(long promptTokens, long outputTokens, int unknownAttempts) {}
}
