package com.privatebank.agent.application.downstream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.agent.application.runtime.AgentExecutionRequest;
import com.privatebank.agent.application.runtime.AgentExecutionResult;
import com.privatebank.agent.application.runtime.BusinessAgentExecutor;
import com.privatebank.agent.application.runtime.StructuredAgentDefinition;
import com.privatebank.agent.application.runtime.StructuredAgentRuntime;
import com.privatebank.agent.config.AgentScopeProperties;
import com.privatebank.agent.domain.downstream.CfsDesignInput;
import com.privatebank.agent.domain.downstream.CfsDesignResult;
import com.privatebank.business.enums.workflow.AgentType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class CfsDesignAgentExecutor implements BusinessAgentExecutor<CfsDesignInput, CfsDesignResult> {

    private static final String SYSTEM_PROMPT = """
            你是私行 CFS 方案设计 Agent。
            基于 KYC、市场洞察和产品专家结果，按照 CFS“3+6”结构生成方案初稿。
            所有结论必须来自输入 Artifact，不得编造事实或证据。
            输出必须严格符合以下 CfsDesignResult 字段格式：
            {
              "customerId": string,
              "inputArtifactRefs": {"kyc": string, "market": string, "kyp": string},
              "cfsVersion": integer,
              "marketingStrategy": string,
              "communicationGuide": string,
              "comprehensiveRiskAssessment": string,
              "cfsStructure": {
                "chapter1CustomerInfo": string,
                "chapter2ServicePlan": string,
                "chapter3MarketingStrategy": string,
                "attachments": [string]
              },
              "pendingVerificationItems": [string],
              "estimatedDataItems": [string],
              "sourceRefs": [string],
              "productEvidenceRefs": [
                {"chunkId": string, "documentId": string, "productId": string,
                 "content": string, "sourceId": string, "score": number}
              ],
              "ruleRefs": [string]
            }
            所有数组字段都必须存在，可以为空数组；数组中的对象元素不能为 null。
            customerId、inputArtifactRefs 中的三个 ID、cfsVersion 必须有效。
            """;

    private final StructuredAgentRuntime runtime;
    private final ObjectMapper objectMapper;
    private final AgentScopeProperties properties;
    private final CfsDesignResultValidator validator = new CfsDesignResultValidator();

    @Override
    public AgentType agentType() {
        return AgentType.SOLUTION_DESIGN;
    }

    @Override
    public AgentExecutionResult<CfsDesignResult> execute(AgentExecutionRequest<CfsDesignInput> request) {
        String lastValidationError = null;
        int attempts = Math.max(1, properties.maxBusinessRepairAttempts());
        for (int attempt = 1; attempt <= attempts; attempt++) {
            StructuredAgentDefinition<CfsDesignResult> definition = new StructuredAgentDefinition<>(
                    "cfs-design-agent",
                    SYSTEM_PROMPT,
                    userPrompt(request.input(), lastValidationError),
                    CfsDesignResult.class,
                    Math.max(1, properties.maxIterations()));
            AgentExecutionResult<CfsDesignResult> result = runtime.execute(request, definition);
            try {
                validator.validate(result.output());
                return new AgentExecutionResult<>(result.output(), attempt, result.modelName());
            } catch (IllegalArgumentException exception) {
                lastValidationError = exception.getMessage();
                log.warn("CFS design structured result failed validation on attempt {}: {}",
                        attempt, lastValidationError);
            }
        }
        throw new IllegalArgumentException(
                "CFS 方案 Agent 连续返回不符合格式要求的结果：" + lastValidationError);
    }

    private String userPrompt(CfsDesignInput input, String validationFailure) {
        String instruction = validationFailure == null
                ? "请基于以下三个上游 Artifact 内容生成 CFS 方案，并严格按照上述字段格式输出。"
                : "上一版 CFS 方案未通过格式校验，请修正后重新生成。失败原因：" + validationFailure;
        return instruction + "\n" + write(input);
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("CFS 方案输入无法序列化", exception);
        }
    }
}
