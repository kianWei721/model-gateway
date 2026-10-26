package com.modelgateway;

import static org.junit.jupiter.api.Assertions.*;

import com.modelgateway.catalog.Catalog;
import com.modelgateway.catalog.UpstreamModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Boots the shipped demo catalog (application.yml, non-test profile) so config mistakes fail the build. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:demo-catalog;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "gateway.secrets.master-keys=v1:AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
        })
@ActiveProfiles("demo")
class DemoCatalogTest {

    @Autowired Catalog catalog;

    @Test
    void shippedCatalogShouldBindAndValidate() {
        assertTrue(catalog.route("rag-json").isPresent());
        assertTrue(catalog.route("reasoning").isPresent());

        UpstreamModel flash = catalog.model("dashscope/qwen3.8-flash").orElseThrow();
        assertEquals(Boolean.FALSE, flash.extraBody().get("enable_thinking"), "typed, not the string \"false\"");

        // Unverified capabilities are not offered: tool and JSON requests skip it.
        UpstreamModel deepseekOnBailian = catalog.model("dashscope/deepseek-v4.1-flash-1017").orElseThrow();
        assertEquals(deepseekOnBailian, catalog.route("standard").orElseThrow().getFirst());
        assertFalse(deepseekOnBailian.supportsTools());
        assertFalse(deepseekOnBailian.supportsJsonOutput());
        assertEquals(Boolean.FALSE, deepseekOnBailian.extraBody().get("enable_thinking"));

        // Agent turns call tools: every candidate must take them, and none may be a protocol the
        // gateway does not translate tool calls for.
        var agent = catalog.route("agent").orElseThrow();
        assertEquals(
                java.util.List.of("dashscope/qwen3.8-flash", "dashscope/qwen3.8-27b"),
                agent.stream().map(UpstreamModel::id).toList());
        assertTrue(agent.stream().allMatch(UpstreamModel::supportsTools));
        assertEquals(Boolean.FALSE, agent.get(1).extraBody().get("enable_thinking"));

        UpstreamModel claude = catalog.model("anthropic/claude-opus-5-5").orElseThrow();
        assertFalse(claude.supportsJsonOutput());
        assertFalse(claude.forwardSampling());
    }
}
