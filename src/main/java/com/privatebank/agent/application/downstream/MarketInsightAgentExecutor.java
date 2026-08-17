package com.privatebank.agent.application.downstream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.agent.application.runtime.AgentExecutionRequest;
import com.privatebank.agent.application.runtime.AgentExecutionResult;
import com.privatebank.agent.application.runtime.BusinessAgentExecutor;
import com.privatebank.agent.application.runtime.StructuredAgentDefinition;
import com.privatebank.agent.application.runtime.StructuredAgentRuntime;
import com.privatebank.agent.config.AgentScopeProperties;
import com.privatebank.agent.domain.downstream.MarketInsightInput;
import com.privatebank.agent.domain.downstream.MarketInsightResult;
import com.privatebank.business.enums.workflow.AgentType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MarketInsightAgentExecutor implements BusinessAgentExecutor<MarketInsightInput, MarketInsightResult> {

    private static final String SYSTEM_PROMPT = """
            你是私行市场洞察专业 Agent。
            基于输入的 KYC 结果，分析客户所处行业、同业竞争格局、本行优势和营销机会。
            所有结论必须来自输入内容，不得编造来源。
            输出必须符合 MarketInsightResult 结构。
            """;

    private final StructuredAgentRuntime runtime;
    private final ObjectMapper objectMapper;
    private final AgentScopeProperties properties;

    @Override
    public AgentType agentType() {
        return AgentType.MARKET_INSIGHT;
    }

    @Override
    public AgentExecutionResult<MarketInsightResult> execute(AgentExecutionRequest<MarketInsightInput> request) {
        StructuredAgentDefinition<MarketInsightResult> definition = new StructuredAgentDefinition<>(
                "market-insight-agent",
                SYSTEM_PROMPT,
                "请基于以下 KYC 结果生成市场洞察。\n" + write(request.input()),
                MarketInsightResult.class,
                Math.max(1, properties.maxIterations()));
        return runtime.execute(request, definition);
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("市场洞察输入无法序列化", exception);
        }
    }
}
