package com.privatebank.agent.application.downstream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.agent.application.runtime.AgentExecutionRequest;
import com.privatebank.agent.application.runtime.AgentExecutionResult;
import com.privatebank.agent.application.runtime.AgentRuntimeException;
import com.privatebank.agent.application.runtime.BusinessAgentExecutor;
import com.privatebank.agent.application.runtime.StructuredAgentDefinition;
import com.privatebank.agent.application.runtime.StructuredAgentRuntime;
import com.privatebank.agent.config.AgentScopeProperties;
import com.privatebank.agent.domain.downstream.ComplianceCheckInput;
import com.privatebank.agent.domain.downstream.ComplianceCheckResult;
import com.privatebank.business.enums.workflow.AgentType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ComplianceCheckAgentExecutor implements BusinessAgentExecutor<ComplianceCheckInput, ComplianceCheckResult> {

    private static final String SYSTEM_PROMPT = """
            你是私行合规校验 Agent。
            检查 CFS 方案的事实来源、字段完整性、产品条件、敏感表达、内部一致性和模板结构。
            输出 complianceResult 只能是 PASS、REJECT 或 REVIEW_REQUIRED。
            所有 findings 必须给出位置、规则、严重程度和修正建议。
            输出必须严格符合以下 ComplianceCheckResult 字段格式：
            {
              "cfsArtifactRef": string,
              "complianceResult": "PASS" | "REJECT" | "REVIEW_REQUIRED",
              "checkSummary": string,
              "findings": [
                {"location": string, "ruleId": string, "severity": string,
                 "message": string, "evidenceRefs": [string], "suggestion": string}
              ],
              "conclusionExplanations": [string],
              "evidenceChain": [string],
              "reviewRequiredItems": [string]
            }
            所有数组字段都必须存在，可以为空数组；数组中的对象元素不能为 null。
            cfsArtifactRef、checkSummary 不能为空，complianceResult 必须是指定枚举值之一。
            """;

    private final StructuredAgentRuntime runtime;
    private final ObjectMapper objectMapper;
    private final AgentScopeProperties properties;
    private final ComplianceCheckResultValidator validator = new ComplianceCheckResultValidator();

    @Override
    public AgentType agentType() {
        return AgentType.COMPLIANCE_CHECK;
    }

    @Override
    public AgentExecutionResult<ComplianceCheckResult> execute(AgentExecutionRequest<ComplianceCheckInput> request) {
        String lastValidationError = null;
        int attempts = Math.max(1, properties.maxBusinessRepairAttempts());
        for (int attempt = 1; attempt <= attempts; attempt++) {
            StructuredAgentDefinition<ComplianceCheckResult> definition = new StructuredAgentDefinition<>(
                    "compliance-check-agent",
                    SYSTEM_PROMPT,
                    userPrompt(request.input(), lastValidationError),
                    ComplianceCheckResult.class,
                    Math.max(1, properties.maxIterations()));
            AgentExecutionResult<ComplianceCheckResult> result = runtime.execute(request, definition);
            try {
                validator.validate(result.output());
                return new AgentExecutionResult<>(result.output(), attempt, result.modelName());
            } catch (IllegalArgumentException exception) {
                lastValidationError = exception.getMessage();
                log.warn("Compliance check structured result failed validation on attempt {}: {}",
                        attempt, lastValidationError);
            }
        }
        throw new AgentRuntimeException(
                "合规检查 Agent 连续返回不符合格式要求的结果", new IllegalArgumentException(lastValidationError));
    }

    private String userPrompt(ComplianceCheckInput input, String validationFailure) {
        String instruction = validationFailure == null
                ? "请对以下 CFS 方案进行合规检查，并严格按照上述字段格式输出。"
                : "上一版合规检查结果未通过格式校验，请修正后重新生成。失败原因：" + validationFailure;
        return instruction + "\n" + write(input);
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("合规检查输入无法序列化", exception);
        }
    }
}
