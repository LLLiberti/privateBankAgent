package com.privatebank.agent.application.downstream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.agent.application.runtime.AgentExecutionRequest;
import com.privatebank.agent.application.runtime.AgentExecutionResult;
import com.privatebank.agent.application.runtime.BusinessAgentExecutor;
import com.privatebank.agent.application.runtime.StructuredAgentDefinition;
import com.privatebank.agent.application.runtime.StructuredAgentRuntime;
import com.privatebank.agent.config.AgentScopeProperties;
import com.privatebank.agent.domain.downstream.ComplianceCheckInput;
import com.privatebank.agent.domain.downstream.ComplianceCheckResult;
import com.privatebank.business.enums.workflow.AgentType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ComplianceCheckAgentExecutor implements BusinessAgentExecutor<ComplianceCheckInput, ComplianceCheckResult> {

    private static final String SYSTEM_PROMPT = """
            你是私行合规校验 Agent。
            检查 CFS 方案的事实来源、字段完整性、产品条件、敏感表达、内部一致性和模板结构。
            输出 complianceResult 只能是 PASS、REJECT 或 REVIEW_REQUIRED。
            所有 findings 必须给出位置、规则、严重程度和修正建议。
            输出必须符合 ComplianceCheckResult 结构。
            """;

    private final StructuredAgentRuntime runtime;
    private final ObjectMapper objectMapper;
    private final AgentScopeProperties properties;

    @Override
    public AgentType agentType() {
        return AgentType.COMPLIANCE_CHECK;
    }

    @Override
    public AgentExecutionResult<ComplianceCheckResult> execute(AgentExecutionRequest<ComplianceCheckInput> request) {
        StructuredAgentDefinition<ComplianceCheckResult> definition = new StructuredAgentDefinition<>(
                "compliance-check-agent",
                SYSTEM_PROMPT,
                "请对以下 CFS 方案进行合规检查。\n" + write(request.input()),
                ComplianceCheckResult.class,
                Math.max(1, properties.maxIterations()));
        return runtime.execute(request, definition);
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("合规检查输入无法序列化", exception);
        }
    }
}
