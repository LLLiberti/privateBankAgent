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
import com.privatebank.agent.domain.downstream.KypRecommendationResult;
import com.privatebank.agent.domain.downstream.ProductExpertInput;
import com.privatebank.business.enums.workflow.AgentType;
import io.agentscope.core.tool.Toolkit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductExpertAgentExecutor implements BusinessAgentExecutor<ProductExpertInput, KypRecommendationResult> {

    private static final String SYSTEM_PROMPT = """
            你是私行产品专家（KYP）Agent。
            你可以调用 search_product_knowledge 工具检索产品知识。
            必须先基于输入的候选产品集合和产品知识，生成推荐、排除和待复核项。
            所有推荐必须引用产品证据，不得编造产品。
            输出必须严格符合以下 KypRecommendationResult 字段格式：
            {
              "mode": string,
              "customerId": string,
              "kycArtifactRef": string,
              "recommendedItems": [
                {"productId": string, "productName": string, "reason": string,
                 "limitations": [string], "evidenceRefs": [string]}
              ],
              "rejectedItems": [
                {"productId": string, "productName": string, "reason": string, "ruleId": string}
              ],
              "reviewRequiredItems": [
                {"productId": string, "productName": string, "reason": string}
              ],
              "ruleCheckResults": [
                {"ruleId": string, "productId": string, "passed": boolean, "message": string}
              ],
              "unresolvedItems": [string],
              "productEvidenceRefs": [
                {"chunkId": string, "documentId": string, "productId": string,
                 "content": string, "sourceId": string, "score": number}
              ]
            }
            所有数组字段都必须存在，可以为空数组；数组中的对象元素不能为 null。
            mode、customerId、kycArtifactRef 不能为空。
            """;

    private final StructuredAgentRuntime runtime;
    private final ObjectMapper objectMapper;
    private final AgentScopeProperties properties;
    private final ProductKnowledgeSearchTool productKnowledgeSearchTool;
    private final KypRecommendationResultValidator validator = new KypRecommendationResultValidator();

    @Override
    public AgentType agentType() {
        return AgentType.PRODUCT_EXPERT;
    }

    @Override
    public AgentExecutionResult<KypRecommendationResult> execute(AgentExecutionRequest<ProductExpertInput> request) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(productKnowledgeSearchTool);
        String lastValidationError = null;
        int attempts = Math.max(1, properties.maxBusinessRepairAttempts());
        for (int attempt = 1; attempt <= attempts; attempt++) {
            StructuredAgentDefinition<KypRecommendationResult> definition = new StructuredAgentDefinition<>(
                    "product-expert-agent",
                    SYSTEM_PROMPT,
                    userPrompt(request.input(), lastValidationError),
                    KypRecommendationResult.class,
                    Math.max(1, properties.maxIterations()),
                    toolkit);
            AgentExecutionResult<KypRecommendationResult> result = runtime.execute(request, definition);
            try {
                validator.validate(result.output());
                return new AgentExecutionResult<>(result.output(), attempt, result.modelName());
            } catch (IllegalArgumentException exception) {
                lastValidationError = exception.getMessage();
                log.warn("Product expert structured result failed validation on attempt {}: {}",
                        attempt, lastValidationError);
            }
        }
        throw new AgentRuntimeException(
                "产品专家 Agent 连续返回不符合格式要求的结果", new IllegalArgumentException(lastValidationError));
    }

    private String userPrompt(ProductExpertInput input, String validationFailure) {
        String instruction = validationFailure == null
                ? "请基于以下 KYC 结果和产品知识生成产品推荐，并严格按照上述字段格式输出。"
                : "上一版产品推荐结果未通过格式校验，请修正后重新生成。失败原因：" + validationFailure;
        return instruction + "\n" + write(input);
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("产品专家输入无法序列化", exception);
        }
    }
}
