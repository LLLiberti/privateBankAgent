package com.privatebank.agent.application.kyc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.agent.application.runtime.AgentExecutionRequest;
import com.privatebank.agent.application.runtime.AgentExecutionResult;
import com.privatebank.agent.application.runtime.StructuredAgentDefinition;
import com.privatebank.agent.application.runtime.StructuredAgentRuntime;
import com.privatebank.agent.config.AgentScopeProperties;
import com.privatebank.agent.domain.kyc.KycGenerationException;
import com.privatebank.agent.domain.kyc.KycMaskedInput;
import com.privatebank.agent.domain.kyc.KycStructuredResult;
import com.privatebank.business.enums.workflow.AgentType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KycAgentExecutorTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void returnsValidatedStructuredResultOnFirstAttempt() {
        AtomicInteger calls = new AtomicInteger();
        StructuredAgentRuntime runtime = runtime(calls, validResult("已完成证据复核"));

        AgentExecutionResult<KycStructuredResult> result = executor(runtime, 2).execute(request());

        assertThat(calls).hasValue(1);
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(result.modelName()).isEqualTo("fake-deepseek");
        assertThat(result.output().summary()).isEqualTo("已完成证据复核");
    }

    @Test
    void repairsOnceWhenStructuredResultViolatesEvidenceRules() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> secondPrompt = new AtomicReference<>();
        StructuredAgentRuntime runtime = new StructuredAgentRuntime() {
            @Override
            public <I, O> AgentExecutionResult<O> execute(
                    AgentExecutionRequest<I> request, StructuredAgentDefinition<O> definition) {
                int call = calls.incrementAndGet();
                if (call == 2) {
                    secondPrompt.set(definition.userPrompt());
                }
                Object output = call == 1
                        ? resultWithEvidence("SRC-NOT-ALLOWED")
                        : validResult("修复后的结论");
                return new AgentExecutionResult<>(definition.outputType().cast(output), 1, "fake-deepseek");
            }
        };

        AgentExecutionResult<KycStructuredResult> result = executor(runtime, 2).execute(request());

        assertThat(calls).hasValue(2);
        assertThat(result.attempts()).isEqualTo(2);
        assertThat(result.output().summary()).isEqualTo("修复后的结论");
        assertThat(secondPrompt.get()).contains(
                "未通过证据、脱敏或业务约束校验",
                "具体失败原因：findings[0].evidenceRefs[0] 不在允许引用集合中",
                "允许引用的全部证据编号：[SRC-1]",
                "不得编造证据编号");
    }

    @Test
    void failsAfterConfiguredBusinessRepairAttempts() {
        AtomicInteger calls = new AtomicInteger();
        StructuredAgentRuntime runtime = runtime(calls, resultWithEvidence("SRC-NOT-ALLOWED"));

        assertThatThrownBy(() -> executor(runtime, 2).execute(request()))
                .isInstanceOf(KycGenerationException.class)
                .hasMessageContaining("连续返回不符合业务约束");
        assertThat(calls).hasValue(2);
    }

    @Test
    void createsExecutionScopedAgentDefinition() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<StructuredAgentDefinition<?>> captured = new AtomicReference<>();
        StructuredAgentRuntime runtime = new StructuredAgentRuntime() {
            @Override
            public <I, O> AgentExecutionResult<O> execute(
                    AgentExecutionRequest<I> request, StructuredAgentDefinition<O> definition) {
                calls.incrementAndGet();
                captured.set(definition);
                return new AgentExecutionResult<>(definition.outputType().cast(validResult("ok")), 1, "fake");
            }
        };

        executor(runtime, 2).execute(request());

        assertThat(calls).hasValue(1);
        assertThat(captured.get().name()).isEqualTo("kyc-agent");
        assertThat(captured.get().outputType()).isEqualTo(KycStructuredResult.class);
        assertThat(captured.get().systemPrompt()).contains(
                "SRC-*", "不得猜测", "dataGaps", "graphAssessment",
                "managerInstruction", "managerEvidence", "MGR-*", "evidenceRefs 最多 10 项",
                "不得根据出生年份", "dataCompleteness");
        assertThat(captured.get().userPrompt()).contains("customer", "riskLevel");
        assertThat(captured.get().userPrompt()).doesNotContain("aliasMappings", "绝不能进入模型的客户名称");
    }

    @Test
    void acceptsIncrementalGraphAssessmentOnlyWhenFindingUsesNeo4jEvidence() {
        KycMaskedInput graphInput = graphInput();
        KycStructuredResult graphResult = new KycStructuredResult(
                KycStructuredResult.RiskLevel.HIGH,
                "图关系提供了结构化事实之外的二跳关联",
                List.of(new KycStructuredResult.Finding(
                        KycStructuredResult.Dimension.ENTERPRISE,
                        KycStructuredResult.RiskLevel.MEDIUM,
                        "发现新增二跳关联",
                        List.of("SRC-2"))),
                List.of(), List.of(), List.of(),
                new KycStructuredResult.GraphAssessment(
                        KycStructuredResult.GraphContribution.INCREMENTAL,
                        "Neo4j 提供了新增关系",
                        List.of("SRC-2")));

        AgentExecutionResult<KycStructuredResult> result = executor(runtime(new AtomicInteger(), graphResult), 1)
                .execute(new AgentExecutionRequest<>(
                        "WF-1", "EXE-1", AgentType.CUSTOMER_INSIGHT, "SYSTEM", graphInput, Map.of()));

        assertThat(result.output().graphAssessment().contribution())
                .isEqualTo(KycStructuredResult.GraphContribution.INCREMENTAL);
    }

    @Test
    void acceptsNoIncrementGraphAssessmentWithoutEvidenceReferences() {
        KycMaskedInput graphInput = graphInput();
        KycStructuredResult noIncrementResult = new KycStructuredResult(
                KycStructuredResult.RiskLevel.LOW,
                "图关系没有形成新增或有效印证",
                List.of(), List.of(), List.of(), List.of(),
                new KycStructuredResult.GraphAssessment(
                        KycStructuredResult.GraphContribution.NO_INCREMENT,
                        "已检查可用图关系，未发现新增或有效印证",
                        List.of()));

        AgentExecutionResult<KycStructuredResult> result = executor(
                runtime(new AtomicInteger(), noIncrementResult), 1)
                .execute(new AgentExecutionRequest<>(
                        "WF-1", "EXE-1", AgentType.CUSTOMER_INSIGHT, "SYSTEM", graphInput, Map.of()));

        assertThat(result.output().graphAssessment().contribution())
                .isEqualTo(KycStructuredResult.GraphContribution.NO_INCREMENT);
        assertThat(result.output().graphAssessment().evidenceRefs()).isEmpty();
    }

    @Test
    void rejectsConfirmatoryGraphAssessmentWithoutEvidenceReferences() {
        KycStructuredResult confirmatoryResult = new KycStructuredResult(
                KycStructuredResult.RiskLevel.LOW,
                "图关系交叉印证已有事实",
                List.of(), List.of(), List.of(), List.of(),
                new KycStructuredResult.GraphAssessment(
                        KycStructuredResult.GraphContribution.CONFIRMATORY,
                        "图关系交叉印证已有事实",
                        List.of()));

        assertThatThrownBy(() -> executor(runtime(new AtomicInteger(), confirmatoryResult), 1)
                .execute(new AgentExecutionRequest<>(
                        "WF-1", "EXE-1", AgentType.CUSTOMER_INSIGHT, "SYSTEM", graphInput(), Map.of())))
                .isInstanceOf(KycGenerationException.class)
                .hasRootCauseMessage("graphAssessment 为 INCREMENTAL 或 CONFIRMATORY 时必须引用 Neo4j 关系证据");
    }

    @Test
    void rejectsEntityShortNamesAndFormattedDirectIdentifiersInOutput() {
        assertThatThrownBy(() -> executor(runtime(new AtomicInteger(), validResult("腾讯存在风险")), 1)
                .execute(request()))
                .isInstanceOf(KycGenerationException.class)
                .hasRootCauseMessage("KYC 结果包含未脱敏直接标识信息(ENTITY_TERM)");

        assertThatThrownBy(() -> executor(runtime(new AtomicInteger(), validResult("联系电话010-12345678")), 1)
                .execute(request()))
                .isInstanceOf(KycGenerationException.class)
                .hasRootCauseMessage("KYC 结果包含未脱敏直接标识信息(PHONE)");
    }

    @Test
    void rejectsFindingWithoutEvidence() {
        KycStructuredResult invalid = new KycStructuredResult(
                KycStructuredResult.RiskLevel.HIGH,
                "缺少证据",
                List.of(new KycStructuredResult.Finding(
                        KycStructuredResult.Dimension.PERSON,
                        KycStructuredResult.RiskLevel.HIGH,
                        "无证据结论",
                        List.of())),
                List.of(), List.of(), List.of(),
                new KycStructuredResult.GraphAssessment(
                        KycStructuredResult.GraphContribution.NOT_AVAILABLE,
                        "没有图关系",
                        List.of()));

        assertThatThrownBy(() -> executor(runtime(new AtomicInteger(), invalid), 1).execute(request()))
                .isInstanceOf(KycGenerationException.class)
                .hasRootCauseMessage("findings[0].evidenceRefs 至少包含一项证据");
    }

    @Test
    void acceptsFindingSupportedByRuntimeManagerEvidence() {
        KycStructuredResult managerEvidenceResult = resultWithEvidence("MGR-1");
        KycMaskedInput input = new KycMaskedInput(
                Map.of("person", Map.of("customer", Map.of("riskLevel", "HIGH")),
                        "managerEvidence", List.of(Map.of(
                                "evidenceRef", "MGR-1",
                                "statement", "P-1近期存在流动性安排",
                                "sourceType", "CUSTOMER_MANAGER_CONFIRMED",
                                "verificationStatus", "CONFIRMED")),
                        "relationshipGraph", Map.of("available", false, "relationshipCount", 0,
                                "evidenceRefs", List.of(), "relationships", List.of())),
                Map.of("SRC-1", 1001L),
                Set.of("MGR-1"),
                Set.of(),
                Map.of(),
                "d".repeat(64));

        AgentExecutionResult<KycStructuredResult> result = executor(
                runtime(new AtomicInteger(), managerEvidenceResult), 1).execute(
                        new AgentExecutionRequest<>(
                                "WF-1", "EXE-1", AgentType.CUSTOMER_INSIGHT, "SYSTEM", input, Map.of()));

        assertThat(result.output().findings().getFirst().evidenceRefs()).containsExactly("MGR-1");
    }

    private KycMaskedInput graphInput() {
        return new KycMaskedInput(
                Map.of(
                        "person", Map.of("customer", Map.of("riskLevel", "HIGH")),
                        "relationshipGraph", Map.of(
                                "available", true,
                                "relationshipCount", 1,
                                "evidenceRefs", List.of("SRC-2"),
                                "relationships", List.of(Map.of("sourceRef", "SRC-2")))),
                Map.of("SRC-1", 1001L, "SRC-2", 2001L),
                Set.of(),
                "c".repeat(64));
    }

    private StructuredAgentRuntime runtime(AtomicInteger calls, KycStructuredResult output) {
        return new StructuredAgentRuntime() {
            @Override
            public <I, O> AgentExecutionResult<O> execute(
                    AgentExecutionRequest<I> request, StructuredAgentDefinition<O> definition) {
                calls.incrementAndGet();
                return new AgentExecutionResult<>(definition.outputType().cast(output), 1, "fake-deepseek");
            }
        };
    }

    private KycAgentExecutor executor(StructuredAgentRuntime runtime, int businessAttempts) {
        AgentScopeProperties properties = new AgentScopeProperties(
                new AgentScopeProperties.DeepSeek(null, null, null, null), 0, 4, businessAttempts);
        return new KycAgentExecutor(runtime, new KycOutputValidator(objectMapper), objectMapper, properties);
    }

    private AgentExecutionRequest<KycMaskedInput> request() {
        return new AgentExecutionRequest<>(
                "WF-1", "EXE-1", AgentType.CUSTOMER_INSIGHT, "SYSTEM", input(), Map.of());
    }

    private KycMaskedInput input() {
        return new KycMaskedInput(
                Map.of("person", Map.of("customer", Map.of("riskLevel", "HIGH")),
                        "relationshipGraph", Map.of("available", false, "relationshipCount", 0,
                                "evidenceRefs", List.of(), "relationships", List.of())),
                Map.of("SRC-1", 1001L),
                Set.of("张三", "星海集团", "腾讯控股有限公司", "腾讯"),
                Map.of("P-1", "绝不能进入模型的客户名称"),
                "a".repeat(64));
    }

    private KycStructuredResult validResult(String summary) {
        return new KycStructuredResult(
                KycStructuredResult.RiskLevel.HIGH,
                summary,
                List.of(new KycStructuredResult.Finding(
                        KycStructuredResult.Dimension.PERSON,
                        KycStructuredResult.RiskLevel.HIGH,
                        "资产风险偏高",
                        List.of("SRC-1"))),
                List.of("风险偏高"),
                List.of("复核风险偏好"),
                List.of(),
                new KycStructuredResult.GraphAssessment(
                        KycStructuredResult.GraphContribution.NOT_AVAILABLE,
                        "当前没有可用的 Neo4j 关系投影",
                        List.of()));
    }

    private KycStructuredResult resultWithEvidence(String evidence) {
        KycStructuredResult valid = validResult("待校验结论");
        return new KycStructuredResult(
                valid.riskLevel(), valid.summary(),
                List.of(new KycStructuredResult.Finding(
                        KycStructuredResult.Dimension.PERSON,
                        KycStructuredResult.RiskLevel.HIGH,
                        "资产风险偏高",
                        List.of(evidence))),
                valid.riskAlerts(), valid.recommendedActions(), valid.dataGaps(), valid.graphAssessment());
    }
}
