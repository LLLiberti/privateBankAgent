package com.privatebank.agent.application.downstream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.agent.application.runtime.AgentExecutionRequest;
import com.privatebank.agent.application.runtime.AgentExecutionResult;
import com.privatebank.agent.application.runtime.BusinessAgentExecutor;
import com.privatebank.agent.application.runtime.StructuredAgentDefinition;
import com.privatebank.agent.application.runtime.StructuredAgentRuntime;
import com.privatebank.agent.config.AgentScopeProperties;
import com.privatebank.agent.domain.downstream.KypRecommendationResult;
import com.privatebank.agent.domain.downstream.ProductExpertInput;
import com.privatebank.business.enums.workflow.AgentType;
import io.agentscope.core.tool.Toolkit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProductExpertAgentExecutor implements BusinessAgentExecutor<ProductExpertInput, KypRecommendationResult> {

    private static final String SYSTEM_PROMPT = """
            你是私行产品专家（KYP）Agent。
            你可以调用 search_product_knowledge 工具检索产品知识。
            必须先基于输入的候选产品集合和产品知识，生成推荐、排除和待复核项。
            所有推荐必须引用产品证据，不得编造产品。
            输出必须符合 KypRecommendationResult 结构。
            """;

    private final StructuredAgentRuntime runtime;
    private final ObjectMapper objectMapper;
    private final AgentScopeProperties properties;
    private final ProductKnowledgeSearchTool productKnowledgeSearchTool;

    @Override
    public AgentType agentType() {
        return AgentType.PRODUCT_EXPERT;
    }

    @Override
    public AgentExecutionResult<KypRecommendationResult> execute(AgentExecutionRequest<ProductExpertInput> request) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(productKnowledgeSearchTool);
        StructuredAgentDefinition<KypRecommendationResult> definition = new StructuredAgentDefinition<>(
                "product-expert-agent",
                SYSTEM_PROMPT,
                "请基于以下 KYC 结果和产品知识生成产品推荐。\n" + write(request.input()),
                KypRecommendationResult.class,
                Math.max(1, properties.maxIterations()),
                toolkit);
        return runtime.execute(request, definition);
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("产品专家输入无法序列化", exception);
        }
    }
}
