package com.privatebank.agent.application.downstream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.agent.application.runtime.AgentExecutionClaim;
import com.privatebank.agent.application.runtime.AgentExecutionRequest;
import com.privatebank.agent.application.runtime.AgentExecutionResult;
import com.privatebank.agent.domain.downstream.CfsDesignInput;
import com.privatebank.agent.domain.downstream.CfsDesignResult;
import com.privatebank.agent.domain.downstream.ComplianceCheckInput;
import com.privatebank.agent.domain.downstream.ComplianceCheckResult;
import com.privatebank.agent.domain.downstream.KypRecommendationResult;
import com.privatebank.agent.domain.downstream.MarketInsightInput;
import com.privatebank.agent.domain.downstream.MarketInsightResult;
import com.privatebank.agent.domain.downstream.ProductExpertInput;
import com.privatebank.agent.infrastructure.workflow.AgentWorkflowStateService;
import com.privatebank.business.entity.workflow.AgentArtifact;
import com.privatebank.business.enums.workflow.AgentType;
import com.privatebank.business.mapper.workflow.AgentArtifactMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DownstreamAgentExecutionService {

    private final AgentWorkflowStateService stateService;
    private final AgentArtifactMapper artifactMapper;
    private final MarketInsightAgentExecutor marketInsightExecutor;
    private final ProductExpertAgentExecutor productExpertExecutor;
    private final CfsDesignAgentExecutor cfsDesignExecutor;
    private final CfsDesignResultValidator cfsDesignResultValidator;
    private final ComplianceCheckAgentExecutor complianceCheckExecutor;
    private final ObjectMapper objectMapper;

    public void executeMarketInsight(String workflowId, String kycArtifactId) {
        Optional<AgentExecutionClaim> optional = stateService.claim(workflowId, AgentType.MARKET_INSIGHT);
        if (optional.isEmpty()) {
            return;
        }
        AgentExecutionClaim claim = optional.get();
        try {
            AgentArtifact kyc = artifact(kycArtifactId);
            MarketInsightInput input = new MarketInsightInput(
                    workflowId, kycArtifactId, kyc.getResult(), "", "");
            AgentExecutionResult<MarketInsightResult> result = marketInsightExecutor.execute(
                    new AgentExecutionRequest<>(workflowId, claim.executionId(), claim.agentType(),
                            claim.operatorUserId(), input, Map.of("kycArtifactId", kycArtifactId)));
            stateService.complete(claim, write(result.output()), null);
        } catch (Exception exception) {
            fail(claim, "MARKET_INSIGHT_EXECUTION_FAILED", exception);
        }
    }

    public void executeProductExpert(String workflowId, String kycArtifactId) {
        Optional<AgentExecutionClaim> optional = stateService.claim(workflowId, AgentType.PRODUCT_EXPERT);
        if (optional.isEmpty()) {
            return;
        }
        AgentExecutionClaim claim = optional.get();
        try {
            AgentArtifact kyc = artifact(kycArtifactId);
            ProductExpertInput input = new ProductExpertInput(
                    workflowId,
                    kycArtifactId,
                    kyc.getResult(),
                    List.of(),
                    List.of());
            AgentExecutionResult<KypRecommendationResult> result = productExpertExecutor.execute(
                    new AgentExecutionRequest<>(workflowId, claim.executionId(), claim.agentType(),
                            claim.operatorUserId(), input, Map.of("kycArtifactId", kycArtifactId)));
            stateService.complete(claim, write(result.output()), null);
        } catch (Exception exception) {
            fail(claim, "PRODUCT_EXPERT_EXECUTION_FAILED", exception);
        }
    }

    public void executeCfsDesign(
            String workflowId,
            String kycArtifactId,
            String marketArtifactId,
            String kypArtifactId) {
        Optional<AgentExecutionClaim> optional = stateService.claim(workflowId, AgentType.SOLUTION_DESIGN);
        if (optional.isEmpty()) {
            return;
        }
        AgentExecutionClaim claim = optional.get();
        try {
            CfsDesignInput input = new CfsDesignInput(
                    workflowId,
                    kycArtifactId,
                    marketArtifactId,
                    kypArtifactId,
                    artifact(kycArtifactId).getResult(),
                    artifact(marketArtifactId).getResult(),
                    artifact(kypArtifactId).getResult(),
                    "CFS-3P6-V1",
                    "INITIAL",
                    null,
                    null);
            AgentExecutionResult<CfsDesignResult> result = cfsDesignExecutor.execute(
                    new AgentExecutionRequest<>(workflowId, claim.executionId(), claim.agentType(),
                            claim.operatorUserId(), input, Map.of(
                            "kycArtifactId", kycArtifactId,
                            "marketArtifactId", marketArtifactId,
                            "kypArtifactId", kypArtifactId)));
            cfsDesignResultValidator.validate(result.output());
            stateService.complete(claim, write(result.output()), null);
        } catch (IllegalArgumentException validationException) {
            fail(claim, "CFS_DESIGN_VALIDATION_FAILED", validationException);
        } catch (Exception exception) {
            fail(claim, "CFS_DESIGN_EXECUTION_FAILED", exception);
        }
    }

    public void executeComplianceCheck(String workflowId, String cfsArtifactId) {
        Optional<AgentExecutionClaim> optional = stateService.claim(workflowId, AgentType.COMPLIANCE_CHECK);
        if (optional.isEmpty()) {
            return;
        }
        AgentExecutionClaim claim = optional.get();
        try {
            ComplianceCheckInput input = new ComplianceCheckInput(
                    workflowId,
                    cfsArtifactId,
                    artifact(cfsArtifactId).getResult(),
                    "BEFORE_REVIEW",
                    "RULE-SET-V1",
                    "CFS-3P6-V1");
            AgentExecutionResult<ComplianceCheckResult> result = complianceCheckExecutor.execute(
                    new AgentExecutionRequest<>(workflowId, claim.executionId(), claim.agentType(),
                            claim.operatorUserId(), input, Map.of("cfsArtifactId", cfsArtifactId)));
            stateService.complete(claim, write(result.output()), result.output().complianceResult());
        } catch (Exception exception) {
            fail(claim, "COMPLIANCE_CHECK_EXECUTION_FAILED", exception);
        }
    }

    private AgentArtifact artifact(String artifactId) {
        AgentArtifact artifact = artifactMapper.selectById(artifactId);
        if (artifact == null) {
            throw new IllegalStateException("Artifact不存在: " + artifactId);
        }
        return artifact;
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Agent结果无法序列化", exception);
        }
    }

    private void fail(AgentExecutionClaim claim, String errorCode, Exception exception) {
        log.warn("{} failed for workflow {}: {}", claim.agentType(), claim.workflowId(), exception.getMessage());
        stateService.fail(claim, errorCode, exception.getMessage());
    }
}
