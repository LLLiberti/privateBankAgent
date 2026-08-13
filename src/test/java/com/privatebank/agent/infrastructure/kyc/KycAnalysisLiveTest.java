package com.privatebank.agent.infrastructure.kyc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.agent.application.kyc.KycAgentExecutor;
import com.privatebank.agent.application.kyc.KycOutputValidator;
import com.privatebank.agent.application.runtime.AgentExecutionRequest;
import com.privatebank.agent.application.runtime.AgentExecutionResult;
import com.privatebank.agent.config.AgentScopeConfiguration;
import com.privatebank.agent.config.AgentScopeProperties;
import com.privatebank.agent.domain.kyc.KycMaskedInput;
import com.privatebank.agent.domain.kyc.KycStructuredResult;
import com.privatebank.agent.infrastructure.agentscope.AgentRuntimeContextFactory;
import com.privatebank.agent.infrastructure.agentscope.AgentScopeExecutionEngine;
import com.privatebank.business.enums.workflow.AgentType;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicit real-model test. The user enables and runs it manually. */
@Tag("live")
@EnabledIfSystemProperty(named = "private-bank.test.live-kyc", matches = "true")
class KycAnalysisLiveTest {

    private static final String API_KEY = configuredProperty("private-bank.agent-runtime.deepseek.api-key", "");
    private static final String BASE_URL = configuredProperty(
            "private-bank.agent-runtime.deepseek.base-url", "https://api.deepseek.com/v1");
    private static final String MODEL = configuredProperty(
            "private-bank.agent-runtime.deepseek.model", "deepseek-v4-flash");

    @Test
    void generatesAContractCompliantKycAnalysisFromMaskedInput() throws Exception {
        Assumptions.assumeTrue(!API_KEY.isBlank(), "请配置 PRIVATE_BANK_DEEPSEEK_API_KEY");
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        AgentScopeProperties properties = new AgentScopeProperties(
                new AgentScopeProperties.DeepSeek(BASE_URL, API_KEY, MODEL, 0D), 2, 4, 2);
        Model model = new AgentScopeConfiguration().privateBankAgentModel(properties);
        AgentScopeExecutionEngine engine = new AgentScopeExecutionEngine(
                model, properties, new AgentRuntimeContextFactory(), ignored -> { });
        KycAgentExecutor executor = new KycAgentExecutor(
                engine, new KycOutputValidator(objectMapper), objectMapper, properties);
        KycMaskedInput input = new KycMaskedInput(
                Map.of(
                        "contractVersion", "kyc-input.v1",
                        "person", Map.of("customer", Map.of("personType", "ENTREPRENEUR", "riskLevel", "MEDIUM"),
                                "financialFacts", List.of(Map.of("factCategory", "ASSET", "amount", 1000000,
                                        "currencyCode", "CNY", "sourceRef", "SRC-1"))),
                        "enterprise", Map.of("relations", List.of(Map.of("relationType", "CONTROLLER",
                                "industryName", "MANUFACTURING", "sourceRef", "SRC-2"))),
                        "family", Map.of("members", List.of()),
                        "social", Map.of("relations", List.of())),
                Map.of("SRC-1", 1001L, "SRC-2", 1002L),
                Set.of("张三", "测试企业"),
                "0".repeat(64));

        AgentExecutionResult<KycStructuredResult> result = executor.execute(new AgentExecutionRequest<>(
                "WF-LIVE", "EXE-LIVE", AgentType.CUSTOMER_INSIGHT, "LIVE-TEST", input, Map.of()));

        String analysis = objectMapper.writeValueAsString(result.output());
        assertThat(result.modelName()).isEqualTo(MODEL);
        assertThat(result.attempts()).isBetween(1, 2);
        assertThat(analysis).doesNotContain("张三", "测试企业", "1001", "1002");
    }

    private static String configuredProperty(String name, String fallback) {
        try {
            List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
                    "application.yml", new FileSystemResource("src/main/resources/application.yml"));
            for (PropertySource<?> source : sources) {
                Object value = source.getProperty(name);
                if (value != null) {
                    return resolveEnvironmentPlaceholder(String.valueOf(value));
                }
            }
            return fallback;
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取真实 KYC 测试配置", exception);
        }
    }

    private static String resolveEnvironmentPlaceholder(String value) {
        if (!value.startsWith("${") || !value.endsWith("}")) {
            return value;
        }
        String expression = value.substring(2, value.length() - 1);
        int separator = expression.indexOf(':');
        String environmentKey = separator < 0 ? expression : expression.substring(0, separator);
        String defaultValue = separator < 0 ? "" : expression.substring(separator + 1);
        String environmentValue = System.getenv(environmentKey);
        return environmentValue == null || environmentValue.isBlank() ? defaultValue : environmentValue;
    }
}
