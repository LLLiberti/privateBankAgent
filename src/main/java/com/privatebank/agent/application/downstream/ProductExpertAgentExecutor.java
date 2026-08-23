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
            你必须先仔细分析 KYC 结果，提取产品风险等级(客户低中高三级风险对应PR1、PR2、PR3三种产品风险等级)、投资需求、限制条件等；
            然后必须调用 search_product_knowledge 工具，根据 KYC 内容检索候选产品和产品知识；
            search_product_knowledge 的 queries 用于候选召回和排序，不等于最终适当性结论；
            产品风险等级、销售状态和指定产品范围是硬性条件，期限、本金安全和收益方式等 KYC 表述应结合证据判断是偏好还是硬限制；
            如果工具返回 METADATA_PREFERENCE_FALLBACK，说明候选只满足硬性准入和部分偏好：
            可以在证据充分时作为带限制的备选推荐，但 limitations 必须明确披露非保本、浮动收益、期限缺失等冲突；
            如果证据不足以判断冲突影响，必须放入 reviewRequiredItems，不得声称产品满足本金安全或确定性收益；
            最后必须基于工具返回的候选产品和产品知识，结合 KYC 生成推荐、排除和待复核项。
            禁止编造工具未返回的产品。
            如果工具没有返回任何候选产品或产品证据，不得虚构推荐，必须在 unresolvedItems 中说明原因。
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
                ? "当前输入的候选产品集合和产品知识可能为空。你必须先调用 search_product_knowledge 工具获取候选产品和产品知识，再基于工具返回结果和 KYC 生成产品推荐，并严格按照上述字段格式输出。"
                : "上一版产品推荐结果未通过校验，请修正后重新生成。失败原因：" + validationFailure;
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
