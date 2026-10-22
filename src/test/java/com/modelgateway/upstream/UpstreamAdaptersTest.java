package com.modelgateway.upstream;

import static org.junit.jupiter.api.Assertions.*;

import com.modelgateway.catalog.Catalog;
import com.modelgateway.catalog.UpstreamModel;
import com.modelgateway.support.FakeUpstream;
import com.modelgateway.support.FakeUpstream.Reply;
import com.modelgateway.support.TestConfigs;
import com.modelgateway.upstream.UpstreamFailure.Kind;
import java.net.ServerSocket;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class UpstreamAdaptersTest {

    private static final FakeUpstream FAKE = FakeUpstream.start();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Catalog CATALOG = new Catalog(TestConfigs.standard(FAKE.baseUrl("")));

    private final UpstreamHttp http = new UpstreamHttp(JSON);
    private final OpenAiCompatibleAdapter openAi = new OpenAiCompatibleAdapter(http);
    private final AnthropicAdapter anthropic = new AnthropicAdapter(http, JSON);

    @AfterAll
    static void stop() {
        FAKE.close();
    }

    @BeforeEach
    void reset() {
        FAKE.reset();
    }

    @Test
    void openAiUsageShouldSplitCachedTokensOutOfInput() {
        TokenUsage openAiStyle =
                OpenAiCompatibleAdapter.usage(
                        json("""
                                {"prompt_tokens":1000,"completion_tokens":300,
                                 "prompt_tokens_details":{"cached_tokens":600},
                                 "completion_tokens_details":{"reasoning_tokens":120}}"""));
        assertEquals(new TokenUsage(400, 600, 0, 300, 120), openAiStyle);

        TokenUsage deepSeekStyle =
                OpenAiCompatibleAdapter.usage(
                        json("""
                                {"prompt_tokens":1000,"completion_tokens":50,
                                 "prompt_cache_hit_tokens":900,"prompt_cache_miss_tokens":100}"""));
        assertEquals(new TokenUsage(100, 900, 0, 50, 0), deepSeekStyle);
    }

    @Test
    void openAiPrepareShouldPinTheUpstreamModelAndDropStreaming() {
        PreparedCall call =
                openAi.prepare(
                        model("dashscope/qwen-plus"),
                        object("""
                                {"model":"standard","stream":true,"max_tokens":256,
                                 "messages":[{"role":"user","content":"你好"}]}"""));

        assertEquals("qwen-plus", call.body().path("model").asString());
        assertFalse(call.body().has("stream"));
        assertEquals(256, call.maxOutputTokens());
        assertEquals(256, call.body().path("max_tokens").asInt(), "the caller's cap is kept, not overwritten");
    }

    @Test
    void missingCapShouldBeSentUpstreamSoTheReservationHolds() {
        PreparedCall call =
                openAi.prepare(model("dashscope/qwen-plus"), object("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"));

        assertEquals(1024, call.maxOutputTokens());
        assertEquals(1024, call.body().path("max_tokens").asInt());
    }

    @Test
    void vendorSwitchesShouldComeFromTheCatalogWithTheirRealType() {
        PreparedCall call =
                openAi.prepare(model("dashscope/qwen3.8-flash"), object("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"));

        assertTrue(call.body().path("enable_thinking").isBoolean());
        assertFalse(call.body().path("enable_thinking").asBoolean());
    }

    @Test
    void anthropicPrepareShouldTranslateSystemTurnsAndStops() {
        PreparedCall call =
                anthropic.prepare(
                        model("anthropic/claude-opus-5-5"),
                        object("""
                                {"model":"reasoning","temperature":0.2,"stop":["END"],
                                 "messages":[
                                   {"role":"system","content":"你是财税助手"},
                                   {"role":"user","content":"问题一"},
                                   {"role":"user","content":[{"type":"text","text":"补充"}]},
                                   {"role":"assistant","content":"好的"},
                                   {"role":"user","content":"继续"}]}"""));

        JsonNode body = call.body();
        assertEquals("claude-opus-5-5", body.path("model").asString());
        assertEquals("你是财税助手", body.path("system").asString());
        assertEquals(1024, body.path("max_tokens").asInt(), "falls back to the model default");
        assertEquals(3, body.path("messages").size(), "consecutive user turns are merged");
        assertEquals("问题一\n\n补充", body.path("messages").get(0).path("content").asString());
        assertEquals("[\"END\"]", body.path("stop_sequences").toString());
        assertFalse(body.has("temperature"), "this model rejects sampling parameters");
        assertFalse(body.has("fallbacks"), "server-side fallback is opt-in");
    }

    @Test
    void anthropicPrepareShouldRefuseWhatItCannotTranslate() {
        UpstreamModel claude = model("anthropic/claude-opus-5-5");

        assertEquals(
                Kind.INCOMPATIBLE,
                assertThrows(
                                UpstreamFailure.class,
                                () ->
                                        anthropic.prepare(
                                                claude,
                                                object("""
                                                        {"messages":[{"role":"user","content":[
                                                          {"type":"image_url","image_url":{"url":"x"}}]}]}""")))
                        .kind());
        assertEquals(
                Kind.INCOMPATIBLE,
                assertThrows(
                                UpstreamFailure.class,
                                () ->
                                        anthropic.prepare(
                                                claude,
                                                object("""
                                                        {"tools":[{"type":"function"}],
                                                         "messages":[{"role":"user","content":"hi"}]}""")))
                        .kind());
    }

    @Test
    void anthropicExecuteShouldSendVersionHeaderAndMapTheAnswer() {
        FAKE.on(
                "/anthropic/v1/messages",
                request ->
                        Reply.json(
                                200,
                                """
                                {"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5",
                                 "content":[{"type":"thinking","thinking":""},{"type":"text","text":"增值税"},{"type":"text","text":"答复"}],
                                 "stop_reason":"end_turn",
                                 "usage":{"input_tokens":100,"cache_read_input_tokens":800,
                                          "cache_creation_input_tokens":50,"output_tokens":40}}"""));
        UpstreamModel claude = model("anthropic/claude-opus-5-5");

        UpstreamResult result =
                anthropic.execute(
                        anthropic.prepare(claude, object("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")),
                        "sk-ant-test",
                        Duration.ofSeconds(2));

        FakeUpstream.Captured sent = FAKE.requestsTo("/anthropic/v1/messages").getFirst();
        assertEquals("sk-ant-test", sent.header("x-api-key"));
        assertEquals(AnthropicAdapter.API_VERSION, sent.header("anthropic-version"));
        assertNull(sent.header("anthropic-beta"));

        assertEquals("增值税答复", result.response().path("choices").get(0).path("message").path("content").asString());
        assertEquals("stop", result.response().path("choices").get(0).path("finish_reason").asString());
        assertEquals(new TokenUsage(100, 800, 50, 40, 0), result.usage());
        assertEquals(950, result.response().path("usage").path("prompt_tokens").asInt());
        assertEquals("claude-opus-5-5", result.servedModel());
        assertFalse(result.upstreamFallback());
    }

    @Test
    void anthropicShouldSurfaceRefusalsAndUpstreamFallbacks() {
        assertEquals("content_filter", AnthropicAdapter.finishReason("refusal"));
        assertEquals("length", AnthropicAdapter.finishReason("max_tokens"));
        assertTrue(
                AnthropicAdapter.servedByFallback(
                        json("{\"iterations\":[{\"type\":\"message\"},{\"type\":\"fallback_message\"}]}")));
    }

    @Test
    void statusCodesShouldMapToGatewayDecisions() {
        UpstreamModel qwen = model("dashscope/qwen-plus");
        PreparedCall call = openAi.prepare(qwen, object("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"));

        assertKind(call, 401, Kind.UNAUTHORIZED);
        assertKind(call, 403, Kind.UPSTREAM_REJECTED);
        assertKind(call, 429, Kind.RATE_LIMITED);
        assertKind(call, 529, Kind.SERVER_ERROR);
        UpstreamFailure badRequest = assertKind(call, 400, Kind.BAD_REQUEST);
        assertEquals("fake error 400", badRequest.getMessage(), "vendor message is passed through");
    }

    @Test
    void slowUpstreamShouldBeAnUnknownOutcomeNotAConnectFailure() {
        FAKE.on("/dashscope/v1/chat/completions", request -> new Reply(200, "{}", 1500));
        PreparedCall call =
                openAi.prepare(model("dashscope/qwen-plus"), object("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"));

        UpstreamFailure failure =
                assertThrows(UpstreamFailure.class, () -> openAi.execute(call, "k", Duration.ofMillis(300)));

        assertEquals(Kind.READ_TIMEOUT, failure.kind());
        assertTrue(failure.kind().billingUnknown());
        assertFalse(failure.kind().failover());
    }

    @Test
    void refusedConnectionShouldBeSafeToFailOver() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        Catalog closed = new Catalog(TestConfigs.standard("http://127.0.0.1:" + closedPort));
        UpstreamModel qwen = closed.model("dashscope/qwen-plus").orElseThrow();

        UpstreamFailure failure =
                assertThrows(
                        UpstreamFailure.class,
                        () ->
                                openAi.execute(
                                        openAi.prepare(qwen, object("{\"messages\":[]}")), "k", Duration.ofSeconds(2)));

        assertEquals(Kind.CONNECT_FAILURE, failure.kind());
        assertTrue(failure.kind().failover());
    }

    private UpstreamFailure assertKind(PreparedCall call, int status, Kind expected) {
        FAKE.on(
                "/dashscope/v1/chat/completions",
                request -> Reply.json(status, "{\"error\":{\"message\":\"fake error " + status + "\"}}"));
        UpstreamFailure failure =
                assertThrows(UpstreamFailure.class, () -> openAi.execute(call, "k", Duration.ofSeconds(2)));
        assertEquals(expected, failure.kind(), "status " + status);
        assertEquals(status, failure.httpStatus());
        return failure;
    }

    private static UpstreamModel model(String id) {
        return CATALOG.model(id).orElseThrow();
    }

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    private static ObjectNode object(String text) {
        return (ObjectNode) JSON.readTree(text);
    }
}
