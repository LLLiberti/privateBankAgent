package com.privatebank.agent.infrastructure.kyc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.agent.application.kyc.KycAnalysisGenerator;
import com.privatebank.agent.application.kyc.KycModelClient;
import com.privatebank.agent.application.kyc.KycOutputValidator;
import com.privatebank.agent.domain.kyc.KycGenerationResult;
import com.privatebank.agent.domain.kyc.KycMaskedInput;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Calls the configured DeepSeek model. It is skipped unless the key is supplied through the
 * environment, so normal tests never send data or consume an API quota.
 */
@Tag("live")
@EnabledIfSystemProperty(named = "private-bank.test.live-kyc", matches = "true")
@SpringJUnitConfig
@ContextConfiguration(classes = KycAnalysisLiveTest.LiveConfiguration.class)
class KycAnalysisLiveTest {

    private static final String API_KEY = configuredProperty("spring.ai.deepseek.api-key", "");
    private static final String BASE_URL = configuredProperty("spring.ai.deepseek.base-url", "https://api.deepseek.com");
    private static final String MODEL = configuredProperty("spring.ai.deepseek.chat.options.model", "deepseek-v4-flash");

    @DynamicPropertySource
    static void deepSeekProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.deepseek.base-url", () -> BASE_URL);
        registry.add("spring.ai.deepseek.api-key", () -> API_KEY);
        registry.add("spring.ai.deepseek.chat.options.model", () -> MODEL);
        registry.add("spring.ai.deepseek.chat.options.temperature", () -> 0);
    }

    @BeforeAll
    static void requireConfiguredApiKey() {
        Assumptions.assumeTrue(!API_KEY.isBlank(),
                "请在 application.yml 或 PRIVATE_BANK_DEEPSEEK_API_KEY 中配置 DeepSeek API Key");
    }

    @org.springframework.beans.factory.annotation.Autowired
    private KycAnalysisGenerator analysisGenerator;

    @org.springframework.beans.factory.annotation.Autowired
    private ObjectMapper objectMapper;

    @Test
    void generatesAContractCompliantKycAnalysisFromMaskedInput() throws Exception {
        KycMaskedInput input = new KycMaskedInput(
                Map.of(
                        "contractVersion", "kyc-input.v1",
                        "person", Map.of("customer", Map.of("personType", "ENTREPRENEUR", "riskLevel", "MEDIUM"),
                                "financialFacts", java.util.List.of(Map.of("factCategory", "ASSET", "amount", 1000000,
                                        "currencyCode", "CNY", "sourceRef", "SRC-1"))),
                        "enterprise", Map.of("relations", java.util.List.of(Map.of("relationType", "CONTROLLER",
                                "industryName", "MANUFACTURING", "sourceRef", "SRC-2"))),
                        "family", Map.of("members", java.util.List.of()),
                        "social", Map.of("relations", java.util.List.of())),
                Map.of("SRC-1", 1001L, "SRC-2", 1002L),
                Set.of("张三", "测试企业"),
                "0".repeat(64));

        KycGenerationResult result = analysisGenerator.generate(input);
        JsonNode analysis = objectMapper.readTree(result.analysisJson());

        System.out.printf("%n[KYC live test] maskedInput=%s%n[KYC live test] modelOutput=%s%n",
                objectMapper.writeValueAsString(input.payload()), result.analysisJson());

        assertThat(result.modelName()).isEqualTo(MODEL);
        assertThat(result.attempts()).isBetween(1, 3);
        assertThat(analysis.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("riskLevel", "summary", "findings", "riskAlerts", "recommendedActions", "dataGaps");
        assertThat(analysis.toString()).doesNotContain("张三", "测试企业", "1001", "1002");
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({
            SpringAiRetryAutoConfiguration.class,
            ToolCallingAutoConfiguration.class,
            DeepSeekChatAutoConfiguration.class
    })
    static class LiveConfiguration {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        KycOutputValidator kycOutputValidator(ObjectMapper objectMapper) {
            return new KycOutputValidator(objectMapper);
        }

        @Bean
        KycModelClient kycModelClient(ChatModel chatModel) {
            return new SpringAiKycModelClient(chatModel);
        }

        @Bean
        KycAnalysisGenerator kycAnalysisGenerator(
                KycModelClient kycModelClient, KycOutputValidator kycOutputValidator, ObjectMapper objectMapper) {
            return new KycAnalysisGenerator(kycModelClient, kycOutputValidator, objectMapper);
        }
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
