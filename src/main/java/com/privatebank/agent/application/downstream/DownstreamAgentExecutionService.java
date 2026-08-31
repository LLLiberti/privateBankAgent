package com.privatebank.agent.application.downstream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.agent.application.runtime.AgentExecutionCompletedEvent;
import com.privatebank.agent.application.runtime.AgentExecutionFailedEvent;
import com.privatebank.agent.application.runtime.AgentExecutionRequest;
import com.privatebank.agent.application.runtime.AgentExecutionRequestedEvent;
import com.privatebank.agent.application.runtime.AgentExecutionResult;
import com.privatebank.agent.domain.downstream.CfsDesignInput;
import com.privatebank.agent.domain.downstream.CfsDesignResult;
import com.privatebank.agent.domain.downstream.ComplianceCheckInput;
import com.privatebank.agent.domain.downstream.ComplianceCheckResult;
import com.privatebank.agent.domain.downstream.KypRecommendationResult;
import com.privatebank.agent.domain.downstream.MarketInsightInput;
import com.privatebank.agent.domain.downstream.MarketInsightResult;
import com.privatebank.agent.domain.downstream.ProductExpertInput;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DownstreamAgentExecutionService {

    private final MarketInsightAgentExecutor marketInsightExecutor;
    private final ProductExpertAgentExecutor productExpertExecutor;
    private final CfsDesignAgentExecutor cfsDesignExecutor;
    private final CfsDesignResultValidator cfsDesignResultValidator;
    private final ComplianceCheckAgentExecutor complianceCheckExecutor;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    public void execute(AgentExecutionRequestedEvent event) {
        switch (event.agentType()) {
            case MARKET_INSIGHT -> executeMarketInsight(event);
            case PRODUCT_EXPERT -> executeProductExpert(event);
            case SOLUTION_DESIGN -> executeCfsDesign(event);
            case COMPLIANCE_CHECK -> executeComplianceCheck(event);
            default -> {
                // CUSTOMER_INSIGHT is handled by KycWorkflowExecutionService.
            }
        }
    }

    private void executeMarketInsight(AgentExecutionRequestedEvent event) {
        String kycArtifactId = artifactId(event, "kycArtifactId");
        try {
            MarketInsightInput input = new MarketInsightInput(
                    event.workflowId(), kycArtifactId, artifactResult(event, "kycArtifactId"), "", "");
            AgentExecutionResult<MarketInsightResult> result = marketInsightExecutor.execute(
                    request(event, input));
            complete(event, write(result.output()), null);
        } catch (Exception exception) {
            fail(event, "MARKET_INSIGHT_EXECUTION_FAILED", exception);
        }
    }

    private void executeProductExpert(AgentExecutionRequestedEvent event) {
        String kycArtifactId = artifactId(event, "kycArtifactId");
        try {
            ProductExpertInput input = new ProductExpertInput(
                    event.workflowId(),
                    kycArtifactId,
                    artifactResult(event, "kycArtifactId"),
                    List.of(),
                    List.of());
            AgentExecutionResult<KypRecommendationResult> result = productExpertExecutor.execute(
                    request(event, input));
            complete(event, write(result.output()), null);
        } catch (Exception exception) {
            fail(event, "PRODUCT_EXPERT_EXECUTION_FAILED", exception);
        }
    }

    private void executeCfsDesign(AgentExecutionRequestedEvent event) {
        try {
            CfsDesignInput input = new CfsDesignInput(
                    event.workflowId(),
                    artifactId(event, "kycArtifactId"),
                    artifactId(event, "marketArtifactId"),
                    artifactId(event, "kypArtifactId"),
                    artifactResult(event, "kycArtifactId"),
                    artifactResult(event, "marketArtifactId"),
                    artifactResult(event, "kypArtifactId"),
                    "CFS-3P6-V1",
                    "INITIAL",
                    null,
                    null);
            AgentExecutionResult<CfsDesignResult> result = cfsDesignExecutor.execute(
                    request(event, input));
            cfsDesignResultValidator.validate(result.output());
            complete(event, write(result.output()), null);
        } catch (IllegalArgumentException validationException) {
            fail(event, "CFS_DESIGN_VALIDATION_FAILED", validationException);
        } catch (Exception exception) {
            fail(event, "CFS_DESIGN_EXECUTION_FAILED", exception);
        }
    }

    private void executeComplianceCheck(AgentExecutionRequestedEvent event) {
        try {
            ComplianceCheckInput input = new ComplianceCheckInput(
                    event.workflowId(),
                    artifactId(event, "cfsArtifactId"),
                    artifactResult(event, "cfsArtifactId"),
                    "BEFORE_REVIEW",
                    "RULE-SET-V1",
                    "CFS-3P6-V1");
            AgentExecutionResult<ComplianceCheckResult> result = complianceCheckExecutor.execute(
                    request(event, input));
            complete(event, write(result.output()), result.output().complianceResult());
        } catch (Exception exception) {
            fail(event, "COMPLIANCE_CHECK_EXECUTION_FAILED", exception);
        }
    }

    private <I> AgentExecutionRequest<I> request(AgentExecutionRequestedEvent event, I input) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.putAll(event.inputArtifactIds());
        if (event.personId() != null) {
            metadata.put("personId", event.personId());
        }
        return new AgentExecutionRequest<>(
                event.workflowId(),
                event.executionId(),
                event.agentType(),
                event.operatorUserId(),
                input,
                Map.copyOf(metadata));
    }

    private String artifactId(AgentExecutionRequestedEvent event, String key) {
        String value = event.inputArtifactIds().get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Agent输入缺少Artifact引用: " + key);
        }
        return value;
    }

    private String artifactResult(AgentExecutionRequestedEvent event, String key) {
        String value = event.inputArtifactResults().get(key);
        if (value == null) {
            throw new IllegalStateException("Agent输入缺少Artifact结果: " + key);
        }
        return value;
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Agent结果无法序列化", exception);
        }
    }

    private void complete(AgentExecutionRequestedEvent event, String resultJson, String complianceResult) {
        eventPublisher.publishEvent(new AgentExecutionCompletedEvent(
                event.workflowId(),
                event.agentStateId(),
                event.agentType(),
                event.executionId(),
                resultJson,
                complianceResult,
                0));
    }

    private void fail(AgentExecutionRequestedEvent event, String errorCode, Exception exception) {
        log.warn("{} failed for workflow {}: {}", event.agentType(), event.workflowId(), exception.getMessage());
        eventPublisher.publishEvent(new AgentExecutionFailedEvent(
                event.workflowId(),
                event.agentStateId(),
                event.agentType(),
                event.executionId(),
                errorCode,
                exception.getMessage()));
    }
}
