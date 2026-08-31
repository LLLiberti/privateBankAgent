package com.privatebank.business.service.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.agent.application.runtime.AgentExecutionCompletedEvent;
import com.privatebank.agent.application.runtime.AgentExecutionFailedEvent;
import com.privatebank.business.entity.workflow.AgentArtifact;
import com.privatebank.business.entity.workflow.AgentState;
import com.privatebank.business.entity.workflow.WorkflowState;
import com.privatebank.business.enums.workflow.AgentStatus;
import com.privatebank.business.enums.workflow.AgentType;
import com.privatebank.business.enums.workflow.WorkflowStatus;
import com.privatebank.business.mapper.workflow.WorkflowStateMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Persists Agent outcomes and exclusively owns subsequent workflow transitions.
 */
@Component
@RequiredArgsConstructor
public class WorkflowAgentResultListener {

    private final WorkflowStateMapper workflowMapper;
    private final WorkflowAgentStateService agentStateService;
    private final WorkflowEventHub eventHub;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onAgentCompleted(AgentExecutionCompletedEvent event) {
        agentStateService.complete(event).ifPresent(result -> {
            switch (event.agentType()) {
                case CUSTOMER_INSIGHT -> handleKycSuccess(result);
                case MARKET_INSIGHT, PRODUCT_EXPERT -> handleParallelSuccess(result);
                case SOLUTION_DESIGN -> handleCfsSuccess(result);
                case COMPLIANCE_CHECK -> handleComplianceSuccess(result);
            }
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onAgentFailed(AgentExecutionFailedEvent event) {
        agentStateService.fail(event).ifPresent(result -> {
            WorkflowState workflow = result.workflow();
            LocalDateTime now = LocalDateTime.now();
            workflow.setWorkflowStatus(WorkflowStatus.FAILED);
            workflow.setErrorCode(event.errorCode());
            workflow.setErrorMessage(event.errorMessage());
            workflow.setFinishTime(now);
            workflow.setUpdatedAt(now);
            requireUpdated(workflowMapper.updateById(workflow));
            String eventName = event.agentType() == AgentType.CUSTOMER_INSIGHT
                    ? "KYC_ANALYSIS_FAILED"
                    : "AGENT_FAILED";
            afterCommit(() -> eventHub.publish(event.workflowId(), eventName, Map.of(
                    "workflowId", event.workflowId(),
                    "agentType", event.agentType(),
                    "status", WorkflowStatus.FAILED,
                    "errorCode", event.errorCode())));
        });
    }

    private void handleKycSuccess(WorkflowAgentStateService.PersistedAgentResult result) {
        WorkflowState workflow = result.workflow();
        AgentArtifact artifact = result.artifact();
        workflow.setWorkflowStatus(WorkflowStatus.WAITING_INPUT);
        workflow.setUpdatedAt(LocalDateTime.now());
        requireUpdated(workflowMapper.updateById(workflow));
        afterCommit(() -> eventHub.publish(workflow.getWorkflowId(), "KYC_ANALYSIS_COMPLETED", Map.of(
                "workflowId", workflow.getWorkflowId(),
                "agentType", AgentType.CUSTOMER_INSIGHT,
                "artifactId", artifact.getArtifactId(),
                "status", WorkflowStatus.WAITING_INPUT)));
    }

    private void handleParallelSuccess(WorkflowAgentStateService.PersistedAgentResult result) {
        String workflowId = result.workflow().getWorkflowId();
        AgentState market = agentStateService.agentState(workflowId, AgentType.MARKET_INSIGHT);
        AgentState product = agentStateService.agentState(workflowId, AgentType.PRODUCT_EXPERT);
        if (market == null || product == null
                || market.getAgentStatus() != AgentStatus.SUCCESS
                || product.getAgentStatus() != AgentStatus.SUCCESS) {
            return;
        }
        AgentArtifact kyc = agentStateService.latestArtifact(workflowId, AgentType.CUSTOMER_INSIGHT);
        AgentArtifact marketArtifact = agentStateService.latestArtifact(workflowId, AgentType.MARKET_INSIGHT);
        AgentArtifact productArtifact = agentStateService.latestArtifact(workflowId, AgentType.PRODUCT_EXPERT);
        if (kyc == null || marketArtifact == null || productArtifact == null) {
            return;
        }
        agentStateService.ready(workflowId, AgentType.SOLUTION_DESIGN);
        afterCommit(() -> eventPublisher.publishEvent(new AgentDispatchRequestedEvent(
                workflowId,
                AgentType.SOLUTION_DESIGN,
                Map.of(
                        "kycArtifactId", kyc.getArtifactId(),
                        "marketArtifactId", marketArtifact.getArtifactId(),
                        "kypArtifactId", productArtifact.getArtifactId()))));
    }

    private void handleCfsSuccess(WorkflowAgentStateService.PersistedAgentResult result) {
        String workflowId = result.workflow().getWorkflowId();
        AgentArtifact artifact = result.artifact();
        agentStateService.ready(workflowId, AgentType.COMPLIANCE_CHECK);
        afterCommit(() -> eventPublisher.publishEvent(new AgentDispatchRequestedEvent(
                workflowId,
                AgentType.COMPLIANCE_CHECK,
                Map.of("cfsArtifactId", artifact.getArtifactId()))));
    }

    private void handleComplianceSuccess(WorkflowAgentStateService.PersistedAgentResult result) {
        WorkflowState workflow = result.workflow();
        AgentArtifact artifact = result.artifact();
        String workflowId = workflow.getWorkflowId();
        String complianceResult = artifact.getComplianceResult();
        LocalDateTime now = LocalDateTime.now();
        boolean passed = "PASS".equalsIgnoreCase(complianceResult);
        boolean requiresHumanReview = "REVIEW_REQUIRED".equalsIgnoreCase(complianceResult);
        if (passed || requiresHumanReview) {
            String cfsArtifactId = cfsArtifactIdFrom(artifact);
            if (cfsArtifactId == null || cfsArtifactId.isBlank()) {
                throw new IllegalStateException("合规结果缺少cfsArtifactRef，无法生成报告");
            }
            workflow.setWorkflowStatus(WorkflowStatus.WAITING_REVIEW);
            workflow.setUpdatedAt(now);
            requireUpdated(workflowMapper.updateById(workflow));
            String eventName = passed ? "COMPLIANCE_PASSED" : "COMPLIANCE_REVIEW_REQUIRED";
            afterCommit(() -> eventHub.publish(workflowId, eventName, Map.of(
                    "workflowId", workflowId,
                    "cfsArtifactId", cfsArtifactId,
                    "complianceArtifactId", artifact.getArtifactId(),
                    "status", WorkflowStatus.WAITING_REVIEW)));
        } else if ("REJECT".equalsIgnoreCase(complianceResult)) {
            workflow.setWorkflowStatus(WorkflowStatus.RUNNING);
            workflow.setUpdatedAt(now);
            requireUpdated(workflowMapper.updateById(workflow));
            agentStateService.ready(workflowId, AgentType.SOLUTION_DESIGN);
            afterCommit(() -> eventPublisher.publishEvent(new AgentDispatchRequestedEvent(
                    workflowId, AgentType.SOLUTION_DESIGN, latestInputRefs(workflowId))));
        } else {
            workflow.setWorkflowStatus(WorkflowStatus.WAITING_INPUT);
            workflow.setUpdatedAt(now);
            requireUpdated(workflowMapper.updateById(workflow));
            afterCommit(() -> eventHub.publish(workflowId, "COMPLIANCE_REVIEW_REQUIRED", Map.of(
                    "workflowId", workflowId,
                    "complianceArtifactId", artifact.getArtifactId(),
                    "status", WorkflowStatus.WAITING_INPUT)));
        }
    }

    private String cfsArtifactIdFrom(AgentArtifact complianceArtifact) {
        try {
            JsonNode root = objectMapper.readTree(complianceArtifact.getResult());
            String reference = root.path("cfsArtifactRef").asText(null);
            return reference == null || reference.isBlank()
                    ? root.path("cfsArtifactId").asText(null)
                    : reference;
        } catch (Exception exception) {
            return null;
        }
    }

    private Map<String, String> latestInputRefs(String workflowId) {
        AgentArtifact kyc = agentStateService.latestArtifact(workflowId, AgentType.CUSTOMER_INSIGHT);
        AgentArtifact market = agentStateService.latestArtifact(workflowId, AgentType.MARKET_INSIGHT);
        AgentArtifact kyp = agentStateService.latestArtifact(workflowId, AgentType.PRODUCT_EXPERT);
        return Map.of(
                "kycArtifactId", kyc == null ? "" : kyc.getArtifactId(),
                "marketArtifactId", market == null ? "" : market.getArtifactId(),
                "kypArtifactId", kyp == null ? "" : kyp.getArtifactId());
    }

    private void requireUpdated(int affected) {
        if (affected != 1) {
            throw new IllegalStateException("工作流状态已被并发修改");
        }
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
