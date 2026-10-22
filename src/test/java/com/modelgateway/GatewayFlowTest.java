package com.modelgateway;

import static org.junit.jupiter.api.Assertions.*;

import com.modelgateway.alert.AlertService;
import com.modelgateway.auth.ClientKeyService;
import com.modelgateway.ledger.LedgerRepository;
import com.modelgateway.providerkeys.ProviderKeyPool;
import com.modelgateway.resilience.UpstreamGuards;
import com.modelgateway.support.FakeUpstream;
import com.modelgateway.support.FakeUpstream.Reply;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** End to end over real HTTP: caller -> gateway -> scripted fake vendors. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GatewayFlowTest {

    private static final FakeUpstream FAKE = FakeUpstream.start();
    private static final String DASHSCOPE = "/dashscope/v1/chat/completions";
    private static final String DEEPSEEK = "/deepseek/v1/chat/completions";
    private static final String ANTHROPIC = "/anthropic/v1/messages";
    private static final String HELLO = "{\"model\":\"standard\",\"messages\":[{\"role\":\"user\",\"content\":\"本季度增值税怎么申报\"}]}";

    @DynamicPropertySource
    static void fakeBase(DynamicPropertyRegistry registry) {
        registry.add("fake.base", () -> FAKE.baseUrl(""));
    }

    @AfterAll
    static void stopFake() {
        FAKE.close();
    }

    @Autowired Environment environment;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProviderKeyPool providerKeys;
    @Autowired ClientKeyService clientKeys;
    @Autowired UpstreamGuards guards;
    @Autowired AlertService alerts;
    @Autowired LedgerRepository ledger;
    @Autowired JsonMapper json;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void freshUpstreams() {
        FAKE.reset();
        jdbc.update("DELETE FROM gw_provider_key");
        providerKeys.reload();
        providerKeys.add("dashscope", "sk-dash-1");
        providerKeys.add("deepseek", "sk-deep-1");
        providerKeys.add("anthropic", "sk-ant-1");
        for (String model : List.of("dashscope/qwen-plus", "deepseek/deepseek-chat", "deepseek/deepseek-reasoner", "anthropic/claude-opus-5-5")) {
            guards.reset(model);
        }
    }

    @Test
    void serverErrorShouldFailOverAndMeterBothAttempts() {
        FAKE.on(DASHSCOPE, request -> Reply.json(503, "{\"error\":{\"message\":\"overloaded\"}}"));
        FAKE.on(DEEPSEEK, request -> openAiOk("deepseek-chat", "{\"prompt_tokens\":1000,\"completion_tokens\":200,\"prompt_cache_hit_tokens\":400}"));

        HttpResponse<String> response = post(key("finance", false), HELLO);

        assertEquals(200, response.statusCode());
        assertEquals("deepseek", header(response, "X-Gateway-Served-Provider"));
        assertEquals("true", header(response, "X-Gateway-Fallback"));
        assertEquals("2", header(response, "X-Gateway-Attempts"));
        assertEquals("dashscope/qwen-plus:SERVER_ERROR;deepseek/deepseek-chat:OK", header(response, "X-Gateway-Trail"));

        List<Map<String, Object>> attempts = ledger.attempts(header(response, "X-Gateway-Request-Id"));
        assertEquals("FAILED", attempts.get(0).get("outcome"));
        assertEquals(503, ((Number) attempts.get(0).get("http_status")).intValue());
        assertEquals(0, ((BigDecimal) attempts.get(0).get("cost")).signum());
        assertEquals("SUCCESS", attempts.get(1).get("outcome"));
        // 600 uncached x 2.00 + 400 cached x 0.50 + 200 output x 8.00, per million tokens
        assertEquals(0, new BigDecimal("0.003").compareTo((BigDecimal) attempts.get(1).get("cost")));
    }

    @Test
    void readTimeoutShouldStopAndBookAnEstimateInsteadOfPayingTwice() {
        FAKE.on(DASHSCOPE, request -> new Reply(200, "{}", 3000));
        FAKE.on(DEEPSEEK, request -> openAiOk("deepseek-chat", usage(10, 10)));

        HttpResponse<String> response = post(key("finance", false), HELLO);

        assertEquals(504, response.statusCode());
        assertEquals("UPSTREAM_OUTCOME_UNKNOWN", body(response).path("error").path("code").asString());
        assertTrue(FAKE.requestsTo(DEEPSEEK).isEmpty(), "no second, possibly double-billed attempt");
        Map<String, Object> attempt = ledger.attempts(header(response, "X-Gateway-Request-Id")).getFirst();
        assertEquals("UNKNOWN", attempt.get("outcome"));
        assertEquals(Boolean.TRUE, attempt.get("cost_estimated"));
        assertTrue(((BigDecimal) attempt.get("cost")).signum() > 0);
    }

    @Test
    void rejectedKeyShouldCoolDownAndTheNextKeyShouldServe() {
        providerKeys.add("dashscope", "sk-dash-2");
        AtomicInteger calls = new AtomicInteger();
        FAKE.on(DASHSCOPE, request -> calls.getAndIncrement() == 0
                ? Reply.json(401, "{\"error\":{\"message\":\"invalid api key\"}}")
                : openAiOk("qwen-plus", usage(20, 5)));

        HttpResponse<String> response = post(key("finance", false), HELLO);

        assertEquals(200, response.statusCode());
        assertEquals("dashscope", header(response, "X-Gateway-Served-Provider"));
        List<Map<String, Object>> attempts = ledger.attempts(header(response, "X-Gateway-Request-Id"));
        assertEquals("UNAUTHORIZED", attempts.get(0).get("failure_kind"));
        assertNotEquals(attempts.get(0).get("provider_key_fingerprint"), attempts.get(1).get("provider_key_fingerprint"));
        assertTrue(alerts.recent().stream().anyMatch(alert -> alert.type().equals("PROVIDER_KEY_UNAUTHORIZED")));
    }

    @Test
    void domesticKeyShouldNeverSendDataOverseas() {
        FAKE.on(ANTHROPIC, request -> Reply.json(200, "{}"));
        FAKE.on("/deepseek/v1/chat/completions", request -> openAiOk("deepseek-reasoner", usage(30, 30)));

        HttpResponse<String> response = post(key("finance", false), HELLO.replace("standard", "reasoning"));

        assertEquals(200, response.statusCode());
        assertEquals("DOMESTIC", header(response, "X-Gateway-Region"));
        assertEquals("false", header(response, "X-Gateway-Fallback"), "the domestic model is this caller's primary");
        assertTrue(FAKE.requestsTo(ANTHROPIC).isEmpty());
    }

    @Test
    void clearedKeyShouldReachClaudeThroughTheTranslatedProtocol() {
        FAKE.on(ANTHROPIC, request -> Reply.json(200, """
                {"id":"msg_9","model":"claude-opus-5-5","content":[{"type":"text","text":"按季申报"}],
                 "stop_reason":"end_turn","usage":{"input_tokens":50,"output_tokens":10}}"""));

        HttpResponse<String> response =
                post(key("finance", true), "{\"model\":\"reasoning\",\"temperature\":0.3,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");

        assertEquals(200, response.statusCode());
        assertEquals("OVERSEAS", header(response, "X-Gateway-Region"));
        assertEquals("按季申报", body(response).path("choices").get(0).path("message").path("content").asString());
        JsonNode sent = json.readTree(FAKE.requestsTo(ANTHROPIC).getFirst().body());
        assertFalse(sent.has("temperature"));
        assertEquals("USD", ledger.attempts(header(response, "X-Gateway-Request-Id")).getFirst().get("currency"));
    }

    @Test
    void badRequestShouldNotFailOver() {
        FAKE.on(DASHSCOPE, request -> Reply.json(400, "{\"error\":{\"message\":\"messages too long\"}}"));
        FAKE.on(DEEPSEEK, request -> openAiOk("deepseek-chat", usage(1, 1)));

        HttpResponse<String> response = post(key("finance", false), HELLO);

        assertEquals(400, response.statusCode());
        assertEquals("messages too long", body(response).path("error").path("message").asString());
        assertTrue(FAKE.requestsTo(DEEPSEEK).isEmpty());
    }

    @Test
    void exhaustedBudgetShouldRejectBeforeAnyUpstreamCall() {
        FAKE.on(DASHSCOPE, request -> openAiOk("qwen-plus", usage(1, 1)));

        HttpResponse<String> response = post(key("tiny", false), HELLO);

        assertEquals(429, response.statusCode());
        assertEquals("BUDGET_EXCEEDED", body(response).path("error").path("code").asString());
        assertTrue(FAKE.requestsTo(DASHSCOPE).isEmpty());
    }

    @Test
    void callerErrorsShouldBeRejectedAtTheDoor() {
        assertEquals(401, post("gk-not-a-real-key", HELLO).statusCode());

        String standardOnly = clientKeys.issue("finance", "p", Set.of("standard"), true, 60).plaintext();
        assertEquals(403, post(standardOnly, HELLO.replace("standard", "reasoning")).statusCode());

        HttpResponse<String> streaming = post(standardOnly, HELLO.replace("{", "{\"stream\":true,"));
        assertEquals("STREAMING_UNSUPPORTED", body(streaming).path("error").path("code").asString());
    }

    @Test
    void reportedModelMismatchShouldBeFlaggedNotHidden() {
        FAKE.on(DASHSCOPE, request -> openAiOk("qwen-turbo", usage(10, 10)));

        HttpResponse<String> response = post(key("finance", false), HELLO);

        assertEquals(200, response.statusCode());
        assertEquals("MISMATCH", header(response, "X-Gateway-Model-Check"));
        assertEquals("qwen-turbo", header(response, "X-Gateway-Served-Model"));
        assertEquals("MISMATCH", ledger.attempts(header(response, "X-Gateway-Request-Id")).getFirst().get("model_check"));
        assertTrue(alerts.recent().stream().anyMatch(alert -> alert.type().equals("MODEL_MISMATCH")));
    }

    @Test
    void repeatedFailuresShouldOpenTheBreakerAndStopHammeringTheUpstream() {
        FAKE.on(DASHSCOPE, request -> Reply.json(503, "{}"));
        FAKE.on(DEEPSEEK, request -> openAiOk("deepseek-chat", usage(1, 1)));
        String key = key("finance", false);

        HttpResponse<String> last = null;
        for (int i = 0; i < 6; i++) {
            last = post(key, HELLO);
            assertEquals(200, last.statusCode());
        }

        assertEquals(5, FAKE.requestsTo(DASHSCOPE).size());
        assertTrue(header(last, "X-Gateway-Trail").startsWith("dashscope/qwen-plus:CIRCUIT_OPEN"));
    }

    @Test
    void perKeyRateLimitShouldApply() {
        FAKE.on(DASHSCOPE, request -> openAiOk("qwen-plus", usage(1, 1)));
        String key = clientKeys.issue("finance", "p", Set.of("standard"), false, 1).plaintext();

        assertEquals(200, post(key, HELLO).statusCode());
        HttpResponse<String> limited = post(key, HELLO);
        assertEquals(429, limited.statusCode());
        assertEquals("RATE_LIMITED", body(limited).path("error").path("code").asString());
    }

    @Test
    void adminApiShouldManageKeysAndReconcileAgainstTheVendorBill() {
        assertEquals(401, admin("POST", "/admin/client-keys", "{}", "wrong-token").statusCode());

        HttpResponse<String> issued = admin("POST", "/admin/client-keys",
                "{\"team\":\"audit\",\"project\":\"recon\",\"routes\":[\"standard\"],\"rpm\":30}", "test-admin-token");
        assertEquals(201, issued.statusCode(), issued.body());
        String clientKey = body(issued).path("key").asString();

        HttpResponse<String> added = admin("POST", "/admin/provider-keys",
                "{\"provider\":\"deepseek\",\"secret\":\"sk-added-by-admin\"}", "test-admin-token");
        assertEquals(201, added.statusCode());
        assertFalse(added.body().contains("sk-added-by-admin"), "secrets go in, never out");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM gw_provider_key WHERE secret_ciphertext LIKE '%sk-added%'", Integer.class));

        FAKE.on(DASHSCOPE, request -> Reply.json(503, "{}"));
        FAKE.on(DEEPSEEK, request -> openAiOk("deepseek-chat", usage(100, 40)));
        assertEquals(200, post(clientKey, HELLO).statusCode());

        JsonNode usageReport = body(admin("GET", "/admin/usage?groupBy=team", null, "test-admin-token"));
        boolean auditRow = false;
        for (JsonNode row : usageReport) {
            auditRow |= "audit".equals(row.path("team").asString(""));
        }
        assertTrue(auditRow);

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        LedgerRepository.ModelTotals metered = ledger.billableTotals("deepseek", today).get("deepseek-chat");
        String bill = """
                {"provider":"deepseek","date":"%s","lines":[
                  {"model":"deepseek-chat","promptTokens":%d,"outputTokens":%d},
                  {"model":"deepseek-coder","promptTokens":5000,"outputTokens":800}]}"""
                .formatted(today, metered.promptTokens(), metered.outputTokens());
        JsonNode verdicts = body(admin("POST", "/admin/reconciliation", bill, "test-admin-token"));

        assertEquals("MATCH", verdictOf(verdicts, "deepseek-chat"));
        assertEquals("NOT_CALLED_BY_GATEWAY", verdictOf(verdicts, "deepseek-coder"),
                "a vendor bill for a model the gateway never called means a leaked key or a bypass");
    }

    private static String verdictOf(JsonNode lines, String model) {
        for (JsonNode line : lines) {
            if (model.equals(line.path("model").asString())) {
                return line.path("verdict").asString();
            }
        }
        return null;
    }

    private String key(String team, boolean allowOverseas) {
        return clientKeys.issue(team, "proj-" + UUID.randomUUID(), Set.of("standard", "reasoning"), allowOverseas, 600).plaintext();
    }

    private static Reply openAiOk(String model, String usage) {
        return Reply.json(200, """
                {"id":"chatcmpl-1","object":"chat.completion","model":"%s",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
                 "usage":%s}""".formatted(model, usage));
    }

    private static String usage(int prompt, int completion) {
        return "{\"prompt_tokens\":%d,\"completion_tokens\":%d}".formatted(prompt, completion);
    }

    private HttpResponse<String> post(String clientKey, String body) {
        return send(HttpRequest.newBuilder(uri("/v1/chat/completions"))
                .header("Authorization", "Bearer " + clientKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());
    }

    private HttpResponse<String> admin(String method, String path, String body, String token) {
        return send(HttpRequest.newBuilder(uri(path))
                .header("X-Admin-Token", token)
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build());
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + environment.getProperty("local.server.port") + path);
    }

    private JsonNode body(HttpResponse<String> response) {
        return json.readTree(response.body());
    }

    private static String header(HttpResponse<String> response, String name) {
        return response.headers().firstValue(name).orElse(null);
    }
}
