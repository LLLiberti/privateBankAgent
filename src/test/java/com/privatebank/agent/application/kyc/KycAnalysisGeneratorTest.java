package com.privatebank.agent.application.kyc;

import com.privatebank.agent.domain.kyc.KycGenerationResult;
import com.privatebank.agent.domain.kyc.KycMaskedInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class KycAnalysisGeneratorTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void retriesWhenModelOutputDoesNotMatchTheContract() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        KycModelClient client = new KycModelClient() {
            @Override
            public String generate(String systemPrompt, String userPrompt) {
                return calls.incrementAndGet() == 1 ? "{\"riskLevel\":\"HIGH\"}" : validResult("脱敏后的高风险提示");
            }

            @Override
            public String modelName() {
                return "fake-deepseek";
            }
        };

        KycGenerationResult result = generator(client).generate(input());

        assertThat(calls).hasValue(2);
        assertThat(result.attempts()).isEqualTo(2);
        assertThat(result.modelName()).isEqualTo("fake-deepseek");
        assertThat(objectMapper.readTree(result.analysisJson()).fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder("riskLevel", "summary", "findings", "riskAlerts", "recommendedActions", "dataGaps");
    }

    @Test
    void retriesWhenAProhibitedIdentifierAppearsInOtherwiseValidJson() {
        AtomicInteger calls = new AtomicInteger();
        KycModelClient client = new KycModelClient() {
            @Override
            public String generate(String systemPrompt, String userPrompt) {
                return calls.incrementAndGet() == 1 ? validResult("张三存在风险") : validResult("客户存在风险");
            }
        };

        KycGenerationResult result = generator(client).generate(input());

        assertThat(calls).hasValue(2);
        assertThat(result.analysisJson()).doesNotContain("张三");
    }

    @Test
    void performsFinalReviewInTheInitialPromptWithoutASecondModelCall() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> systemPrompt = new AtomicReference<>();
        AtomicReference<String> userPrompt = new AtomicReference<>();
        KycModelClient client = new KycModelClient() {
            @Override
            public String generate(String suppliedSystemPrompt, String suppliedUserPrompt) {
                calls.incrementAndGet();
                systemPrompt.set(suppliedSystemPrompt);
                userPrompt.set(suppliedUserPrompt);
                return validResult("内部复核后的结论");
            }
        };

        KycGenerationResult result = generator(client).generate(input());

        assertThat(calls).hasValue(1);
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(result.analysisJson()).contains("内部复核后的结论");
        assertThat(systemPrompt.get()).contains("内部完成最终复核", "逐项审计", "evidenceRefs");
        assertThat(userPrompt.get()).contains("内部逐条自检后输出结论");
    }

    @Test
    void returnsImmediatelyWhenTheFirstGeneratedResultPassesValidation() {
        AtomicInteger calls = new AtomicInteger();
        KycModelClient client = new KycModelClient() {
            @Override
            public String generate(String systemPrompt, String userPrompt) {
                calls.incrementAndGet();
                return validResult("首次生成的最终结论");
            }
        };

        KycGenerationResult result = generator(client).generate(input());

        assertThat(calls).hasValue(1);
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(result.analysisJson()).contains("首次生成的最终结论");
    }

    private KycAnalysisGenerator generator(KycModelClient client) {
        KycAnalysisGenerator generator = new KycAnalysisGenerator(client, new KycOutputValidator(objectMapper), objectMapper);
        ReflectionTestUtils.setField(generator, "maxOutputAttempts", 3);
        return generator;
    }

    private KycMaskedInput input() {
        return new KycMaskedInput(
                Map.of("person", Map.of("customer", Map.of("riskLevel", "HIGH"))),
                Map.of("SRC-1", 1001L),
                Set.of("张三", "星海集团"),
                "a".repeat(64));
    }

    private String validResult(String summary) {
        return """
                {"riskLevel":"HIGH","summary":"%s","findings":[{"dimension":"PERSON","riskLevel":"HIGH","finding":"资产风险偏高","evidenceRefs":["SRC-1"]}],"riskAlerts":["风险偏高"],"recommendedActions":["复核风险偏好"],"dataGaps":[]}
                """.formatted(summary);
    }
}
