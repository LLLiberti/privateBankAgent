package com.privatebank.agent.application.downstream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.agent.application.runtime.AgentExecutionRequest;
import com.privatebank.agent.application.runtime.AgentExecutionResult;
import com.privatebank.agent.application.runtime.StructuredAgentDefinition;
import com.privatebank.agent.application.runtime.StructuredAgentRuntime;
import com.privatebank.agent.config.AgentScopeProperties;
import com.privatebank.agent.domain.downstream.KypRecommendationResult;
import com.privatebank.agent.domain.downstream.ProductExpertInput;
import com.privatebank.business.enums.workflow.AgentType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductExpertAgentExecutorTest {

    @Test
    void exposesProductSearchToolAndRequiresSearchBeforeRecommendation() {
        StructuredAgentRuntime runtime = mock(StructuredAgentRuntime.class);
        ProductKnowledgeSearchService searchService = mock(ProductKnowledgeSearchService.class);
        ProductExpertAgentExecutor executor = new ProductExpertAgentExecutor(
                runtime,
                new ObjectMapper().findAndRegisterModules(),
                new AgentScopeProperties(null, 1, 4, 1),
                new ProductKnowledgeSearchTool(searchService));
        KypRecommendationResult output = new KypRecommendationResult(
                "KYP", "C-1", "ART-KYC", List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        AgentExecutionResult<KypRecommendationResult> runtimeResult =
                new AgentExecutionResult<>(output, 1, "model");
        AtomicReference<StructuredAgentDefinition<KypRecommendationResult>> captured = new AtomicReference<>();
        when(runtime.execute(any(), any())).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(1));
            return runtimeResult;
        });
        AgentExecutionRequest<ProductExpertInput> request = new AgentExecutionRequest<>(
                "WF-1",
                "EXE-1",
                AgentType.PRODUCT_EXPERT,
                "USER-1",
                new ProductExpertInput("WF-1", "ART-KYC", "{\"analysis\":{}}", List.of(), List.of()),
                Map.of());

        AgentExecutionResult<KypRecommendationResult> result = executor.execute(request);

        StructuredAgentDefinition<KypRecommendationResult> definition = captured.get();
        assertThat(result.output()).isSameAs(output);
        assertThat(definition).isNotNull();
        assertThat(definition.toolkit().getToolNames()).contains("search_product_knowledge");
        assertThat(definition.systemPrompt())
                .contains("必须调用 search_product_knowledge")
                .contains("禁止编造工具未返回的产品");
        assertThat(definition.userPrompt())
                .contains("必须先调用 search_product_knowledge")
                .contains("\"candidateProductIds\":[]")
                .contains("\"productKnowledge\":[]");
    }
}
