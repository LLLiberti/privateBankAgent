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
import com.privatebank.agent.domain.downstream.MarketInsightInput;
import com.privatebank.agent.domain.downstream.MarketInsightResult;
import com.privatebank.business.enums.workflow.AgentType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketInsightAgentExecutor implements BusinessAgentExecutor<MarketInsightInput, MarketInsightResult> {

    private static final String SYSTEM_PROMPT = """
            你是私行市场洞察专业 Agent。
            基于输入的 KYC 结果，分析客户所处行业、同业竞争格局、本行优势和营销机会。
            所有结论必须来自输入内容，不得编造来源。
            输出必须严格符合以下 MarketInsightResult 字段格式：
            {
              "customerId": string,
              "kycArtifactRef": string,
              "industryInsights": [object],
              "competitorInsights": [object],
              "bankAdvantageMappings": [object],
              "differentiatedViews": [object],
              "marketingOpportunities": [object],
              "riskFlags": [object],
              "unresolvedItems": [object],
              "sourceRefs": [string]
            }
            所有数组字段都必须存在，可以为空数组；数组中的对象元素不能为 null。
            customerId、kycArtifactRef 不能为空。
            """;

    private final StructuredAgentRuntime runtime;
    private final ObjectMapper objectMapper;
    private final AgentScopeProperties properties;
    private final MarketInsightResultValidator validator = new MarketInsightResultValidator();

    @Override
    public AgentType agentType() {
        return AgentType.MARKET_INSIGHT;
    }

    @Override
    public AgentExecutionResult<MarketInsightResult> execute(AgentExecutionRequest<MarketInsightInput> request) {
        String lastValidationError = null;
        int attempts = Math.max(1, properties.maxBusinessRepairAttempts());
        for (int attempt = 1; attempt <= attempts; attempt++) {
            StructuredAgentDefinition<MarketInsightResult> definition = new StructuredAgentDefinition<>(
                    "market-insight-agent",
                    SYSTEM_PROMPT,
                    userPrompt(request.input(), lastValidationError),
                    MarketInsightResult.class,
                    Math.max(1, properties.maxIterations()));
            AgentExecutionResult<MarketInsightResult> result = runtime.execute(request, definition);
            try {
                validator.validate(result.output());
                return new AgentExecutionResult<>(result.output(), attempt, result.modelName());
            } catch (IllegalArgumentException exception) {
                lastValidationError = exception.getMessage();
                log.warn("Market insight structured result failed validation on attempt {}: {}",
                        attempt, lastValidationError);
            }
        }
        throw new AgentRuntimeException(
                "市场洞察 Agent 连续返回不符合格式要求的结果", new IllegalArgumentException(lastValidationError));
    }

    private String userPrompt(MarketInsightInput input, String validationFailure) {
        String instruction = validationFailure == null
                ? "请基于以下 KYC 结果生成市场洞察，并严格按照上述字段格式输出。"
                : "上一版市场洞察结果未通过格式校验，请修正后重新生成。失败原因：" + validationFailure;
        return instruction + "\n" + write(input);
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("市场洞察输入无法序列化", exception);
        }
    }
}
