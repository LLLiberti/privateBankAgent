package com.privatebank.agent.infrastructure.kyc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.agent.application.kyc.KycAnalysisGenerator;
import com.privatebank.agent.application.kyc.KycModelClient;
import com.privatebank.agent.application.kyc.KycOutputValidator;
import com.privatebank.agent.domain.kyc.KycGenerationResult;
import com.privatebank.agent.domain.kyc.KycMaskedInput;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Calls the configured DeepSeek model. It is skipped unless the key is supplied through the
 * environment, so normal tests never send data or consume an API quota.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "PRIVATE_BANK_DEEPSEEK_API_KEY", matches = ".+")
@SpringJUnitConfig
@ContextConfiguration(classes = KycAnalysisLiveTest.LiveConfiguration.class)
@TestPropertySource(properties = {
        "spring.ai.deepseek.base-url=https://api.deepseek.com",
        "spring.ai.deepseek.api-key=${PRIVATE_BANK_DEEPSEEK_API_KEY}",
        "spring.ai.deepseek.chat.options.model=deepseek-v4-flash",
        "spring.ai.deepseek.chat.options.temperature=0"
})
class KycAnalysisLiveTest {

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

        assertThat(result.modelName()).isEqualTo("deepseek-v4-flash");
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
}
