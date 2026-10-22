package com.modelgateway.config;

import com.modelgateway.alert.AlertService;
import com.modelgateway.alert.AlertSink;
import com.modelgateway.alert.LogAlertSink;
import com.modelgateway.alert.WeComWebhookAlertSink;
import com.modelgateway.api.AdminTokenInterceptor;
import com.modelgateway.api.ClientKeyInterceptor;
import com.modelgateway.auth.ClientKeyRepository;
import com.modelgateway.auth.ClientKeyService;
import com.modelgateway.billing.BudgetService;
import com.modelgateway.billing.RpmLimiter;
import com.modelgateway.catalog.Catalog;
import com.modelgateway.core.GatewayService;
import com.modelgateway.integrity.ReconciliationService;
import com.modelgateway.ledger.LedgerRepository;
import com.modelgateway.providerkeys.ProviderKeyPool;
import com.modelgateway.providerkeys.ProviderKeyRepository;
import com.modelgateway.resilience.UpstreamGuards;
import com.modelgateway.routing.RoutePlanner;
import com.modelgateway.secrets.SecretCipher;
import com.modelgateway.upstream.AnthropicAdapter;
import com.modelgateway.upstream.OpenAiCompatibleAdapter;
import com.modelgateway.upstream.UpstreamHttp;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.json.JsonMapper;

/** Plain constructors wired here, so every component stays testable without Spring. */
@Configuration
public class GatewayConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    Catalog catalog(GatewayProperties properties) {
        return new Catalog(properties);
    }

    @Bean
    RoutePlanner routePlanner(Catalog catalog) {
        return new RoutePlanner(catalog);
    }

    @Bean
    SecretCipher secretCipher(GatewayProperties properties) {
        return new SecretCipher(properties.secrets().masterKeys(), properties.secrets().currentMasterKeyVersion());
    }

    @Bean
    ProviderKeyPool providerKeyPool(JdbcTemplate jdbcTemplate, SecretCipher cipher, Catalog catalog, Clock clock) {
        ProviderKeyPool pool = new ProviderKeyPool(new ProviderKeyRepository(jdbcTemplate), cipher, clock, Duration.ofMinutes(10));
        pool.bootstrap(catalog, System::getenv);
        return pool;
    }

    @Bean
    UpstreamHttp upstreamHttp(JsonMapper jsonMapper) {
        return new UpstreamHttp(jsonMapper);
    }

    @Bean
    OpenAiCompatibleAdapter openAiCompatibleAdapter(UpstreamHttp http) {
        return new OpenAiCompatibleAdapter(http);
    }

    @Bean
    AnthropicAdapter anthropicAdapter(UpstreamHttp http, JsonMapper jsonMapper) {
        return new AnthropicAdapter(http, jsonMapper);
    }

    @Bean
    UpstreamGuards upstreamGuards(Catalog catalog) {
        return new UpstreamGuards(catalog, UpstreamGuards.defaultConfig());
    }

    @Bean
    AlertService alertService(GatewayProperties properties, JsonMapper jsonMapper, Clock clock) {
        List<AlertSink> sinks = new ArrayList<>();
        sinks.add(new LogAlertSink());
        String webhook = properties.alerts().wecomWebhookUrl();
        if (webhook != null && !webhook.isBlank()) {
            sinks.add(new WeComWebhookAlertSink(webhook, jsonMapper));
        }
        return new AlertService(sinks, clock, properties.alerts().dedupWindow());
    }

    @Bean
    LedgerRepository ledgerRepository(JdbcTemplate jdbcTemplate) {
        return new LedgerRepository(jdbcTemplate);
    }

    @Bean
    BudgetService budgetService(GatewayProperties properties, LedgerRepository ledger, AlertService alerts, Clock clock) {
        return new BudgetService(
                properties.dailyBudgets(), ledger, alerts, properties.alerts().budgetThresholds(), clock, properties.billingZone());
    }

    @Bean
    RpmLimiter rpmLimiter(Clock clock) {
        return new RpmLimiter(clock);
    }

    @Bean
    GatewayService gatewayService(
            GatewayProperties properties,
            RoutePlanner planner,
            OpenAiCompatibleAdapter openAi,
            AnthropicAdapter anthropic,
            UpstreamGuards guards,
            ProviderKeyPool keys,
            BudgetService budgets,
            RpmLimiter rpmLimiter,
            LedgerRepository ledger,
            AlertService alerts,
            JsonMapper jsonMapper,
            Clock clock) {
        return new GatewayService(
                planner,
                List.of(openAi, anthropic),
                guards,
                keys,
                budgets,
                rpmLimiter,
                ledger,
                alerts,
                jsonMapper,
                clock,
                properties.deadline(),
                properties.attemptTimeout(),
                properties.maxAttempts());
    }

    @Bean
    ClientKeyService clientKeyService(JdbcTemplate jdbcTemplate, Catalog catalog, Clock clock) {
        return new ClientKeyService(new ClientKeyRepository(jdbcTemplate), catalog, clock);
    }

    @Bean
    ReconciliationService reconciliationService(LedgerRepository ledger, AlertService alerts) {
        return new ReconciliationService(ledger, alerts);
    }

    @Bean
    WebMvcConfigurer gatewayInterceptors(GatewayProperties properties, ClientKeyService clientKeys, JsonMapper jsonMapper) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new ClientKeyInterceptor(clientKeys, jsonMapper)).addPathPatterns("/v1/**");
                registry.addInterceptor(new AdminTokenInterceptor(properties.adminToken())).addPathPatterns("/admin/**");
            }
        };
    }
}
