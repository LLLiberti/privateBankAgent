package com.privatebank.business.service.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.agent.domain.event.AgentExecutionRequestedEvent;
import com.privatebank.agent.domain.event.AgentFailedEvent;
import com.privatebank.agent.domain.event.AgentSucceededEvent;
import com.privatebank.agent.infrastructure.workflow.AgentWorkflowStateService;
import com.privatebank.business.entity.workflow.AgentArtifact;
import com.privatebank.business.entity.workflow.AgentState;
import com.privatebank.business.entity.workflow.WorkflowState;
import com.privatebank.business.enums.workflow.AgentStatus;
import com.privatebank.business.enums.workflow.AgentType;
import com.privatebank.business.enums.workflow.WorkflowStatus;
import com.privatebank.business.mapper.workflow.AgentArtifactMapper;
import com.privatebank.business.mapper.workflow.AgentStateMapper;
import com.privatebank.business.mapper.workflow.WorkflowStateMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * The workflow owns workflow_state transitions. Agents publish only committed
 * outcome references; this listener validates that an outcome is still current
 * before exposing the next workflow state.
 */
@Component
@RequiredArgsConstructor
public class WorkflowAgentResultListener {

    private final WorkflowStateMapper workflowMapper;
    private final AgentStateMapper agentStateMapper;
    private final AgentArtifactMapper artifactMapper;
    private final AgentWorkflowStateService agentWorkflowStateService;
    private final WorkflowEventHub eventHub;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @Transactional
    @EventListener
    public void onAgentSucceeded(AgentSucceededEvent event) {
        switch (event.agentType()) {
            case CUSTOMER_INSIGHT -> handleKycSuccess(event);
            case MARKET_INSIGHT, PRODUCT_EXPERT -> handleParallelSuccess(event);
            case SOLUTION_DESIGN -> handleCfsSuccess(event);
            case COMPLIANCE_CHECK -> handleComplianceSuccess(event);
        }
    }

    @Transactional
    @EventListener
    public void onAgentFailed(AgentFailedEvent event) {
        if (event.agentType() == AgentType.CUSTOMER_INSIGHT) {
            handleKycFailure(event);
            return;
        }
        WorkflowState workflow = workflowMapper.selectById(event.workflowId());
        AgentState state = agentStateMapper.selectById(event.agentStateId());
        if (!isCurrentFailure(event, workflow, state)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        workflow.setWorkflowStatus(WorkflowStatus.FAILED);
        workflow.setErrorCode(event.errorCode());
        workflow.setErrorMessage(state.getErrorMessage());
        workflow.setFinishTime(now);
        workflow.setUpdatedAt(now);
        requireUpdated(workflowMapper.updateById(workflow));
        afterCommit(() -> eventHub.publish(event.workflowId(), "AGENT_FAILED", Map.of(
                "workflowId", event.workflowId(),
                "agentType", event.agentType(),
                "status", WorkflowStatus.FAILED,
                "errorCode", event.errorCode())));
    }

    private void handleKycSuccess(AgentSucceededEvent event) {
        WorkflowState workflow = workflowMapper.selectById(event.workflowId());
        AgentState state = agentStateMapper.selectById(event.agentStateId());
        AgentArtifact artifact = artifactMapper.selectById(event.artifactId());
        if (!isCurrentSuccess(event, workflow, state, artifact)) {
            return;
        }
        workflow.setWorkflowStatus(WorkflowStatus.WAITING_INPUT);
        workflow.setUpdatedAt(LocalDateTime.now());
        requireUpdated(workflowMapper.updateById(workflow));
        afterCommit(() -> eventHub.publish(event.workflowId(), "KYC_ANALYSIS_COMPLETED", Map.of(
                "workflowId", event.workflowId(),
                "agentType", AgentType.CUSTOMER_INSIGHT,
                "artifactId", event.artifactId(),
                "status", WorkflowStatus.WAITING_INPUT)));
    }

    private void handleKycFailure(AgentFailedEvent event) {
        WorkflowState workflow = workflowMapper.selectById(event.workflowId());
        AgentState state = agentStateMapper.selectById(event.agentStateId());
        if (!isCurrentFailure(event, workflow, state)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        workflow.setWorkflowStatus(WorkflowStatus.FAILED);
        workflow.setErrorCode(event.errorCode());
        workflow.setErrorMessage(state.getErrorMessage());
        workflow.setFinishTime(now);
        workflow.setUpdatedAt(now);
        requireUpdated(workflowMapper.updateById(workflow));
        afterCommit(() -> eventHub.publish(event.workflowId(), "KYC_ANALYSIS_FAILED", Map.of(
                "workflowId", event.workflowId(),
                "agentType", AgentType.CUSTOMER_INSIGHT,
                "status", WorkflowStatus.FAILED,
                "errorCode", event.errorCode())));
    }

    private void handleParallelSuccess(AgentSucceededEvent event) {
        WorkflowState workflow = workflowMapper.selectById(event.workflowId());
        AgentState state = agentStateMapper.selectById(event.agentStateId());
        AgentArtifact artifact = artifactMapper.selectById(event.artifactId());
        if (!isCurrentSuccess(event, workflow, state, artifact)) {
            return;
        }
        AgentState market = agentWorkflowStateService.agentState(event.workflowId(), AgentType.MARKET_INSIGHT);
        AgentState product = agentWorkflowStateService.agentState(event.workflowId(), AgentType.PRODUCT_EXPERT);
        if (market == null || product == null
                || market.getAgentStatus() != AgentStatus.SUCCESS
                || product.getAgentStatus() != AgentStatus.SUCCESS) {
            return;
        }
        AgentArtifact kyc = agentWorkflowStateService.latestArtifact(event.workflowId(), AgentType.CUSTOMER_INSIGHT);
        AgentArtifact marketArtifact = agentWorkflowStateService.latestArtifact(event.workflowId(), AgentType.MARKET_INSIGHT);
        AgentArtifact productArtifact = agentWorkflowStateService.latestArtifact(event.workflowId(), AgentType.PRODUCT_EXPERT);
        if (kyc == null || marketArtifact == null || productArtifact == null) {
            return;
        }
        agentWorkflowStateService.ready(event.workflowId(), AgentType.SOLUTION_DESIGN);
        afterCommit(() -> eventPublisher.publishEvent(new AgentExecutionRequestedEvent(
                event.workflowId(), AgentType.SOLUTION_DESIGN, Map.of(
                        "kycArtifactId", kyc.getArtifactId(),
                        "marketArtifactId", marketArtifact.getArtifactId(),
                        "kypArtifactId", productArtifact.getArtifactId()))));
    }

    private void handleCfsSuccess(AgentSucceededEvent event) {
        WorkflowState workflow = workflowMapper.selectById(event.workflowId());
        AgentState state = agentStateMapper.selectById(event.agentStateId());
        AgentArtifact artifact = artifactMapper.selectById(event.artifactId());
        if (!isCurrentSuccess(event, workflow, state, artifact)) {
            return;
        }
        agentWorkflowStateService.ready(event.workflowId(), AgentType.COMPLIANCE_CHECK);
        afterCommit(() -> eventPublisher.publishEvent(new AgentExecutionRequestedEvent(
                event.workflowId(), AgentType.COMPLIANCE_CHECK,
                Map.of("cfsArtifactId", artifact.getArtifactId()))));
    }

    private void handleComplianceSuccess(AgentSucceededEvent event) {
        WorkflowState workflow = workflowMapper.selectById(event.workflowId());
        AgentState state = agentStateMapper.selectById(event.agentStateId());
        AgentArtifact artifact = artifactMapper.selectById(event.artifactId());
        if (!isCurrentSuccess(event, workflow, state, artifact)) {
            return;
        }
        String complianceResult = artifact.getComplianceResult();
        LocalDateTime now = LocalDateTime.now();
        if ("PASS".equalsIgnoreCase(complianceResult)) {
            workflow.setWorkflowStatus(WorkflowStatus.WAITING_REVIEW);
            workflow.setUpdatedAt(now);
            requireUpdated(workflowMapper.updateById(workflow));
            afterCommit(() -> eventHub.publish(event.workflowId(), "COMPLIANCE_PASSED", Map.of(
                    "workflowId", event.workflowId(),
                    "cfsArtifactId", cfsArtifactIdFrom(artifact),
                    "complianceArtifactId", artifact.getArtifactId(),
                    "status", WorkflowStatus.WAITING_REVIEW)));
        } else if ("REJECT".equalsIgnoreCase(complianceResult)) {
            workflow.setWorkflowStatus(WorkflowStatus.RUNNING);
            workflow.setUpdatedAt(now);
            requireUpdated(workflowMapper.updateById(workflow));
            agentWorkflowStateService.ready(event.workflowId(), AgentType.SOLUTION_DESIGN);
            afterCommit(() -> eventPublisher.publishEvent(new AgentExecutionRequestedEvent(
                    event.workflowId(), AgentType.SOLUTION_DESIGN, latestInputRefs(event.workflowId()))));
        } else {
            workflow.setWorkflowStatus(WorkflowStatus.WAITING_INPUT);
            workflow.setUpdatedAt(now);
            requireUpdated(workflowMapper.updateById(workflow));
            afterCommit(() -> eventHub.publish(event.workflowId(), "COMPLIANCE_REVIEW_REQUIRED", Map.of(
                    "workflowId", event.workflowId(),
                    "complianceArtifactId", artifact.getArtifactId(),
                    "status", WorkflowStatus.WAITING_INPUT)));
        }
    }

    private String cfsArtifactIdFrom(AgentArtifact complianceArtifact) {
        try {
            JsonNode root = objectMapper.readTree(complianceArtifact.getResult());
            return root.path("cfsArtifactRef").asText(null);
        } catch (Exception exception) {
            return null;
        }
    }

    private Map<String, String> latestInputRefs(String workflowId) {
        AgentArtifact kyc = agentWorkflowStateService.latestArtifact(workflowId, AgentType.CUSTOMER_INSIGHT);
        AgentArtifact market = agentWorkflowStateService.latestArtifact(workflowId, AgentType.MARKET_INSIGHT);
        AgentArtifact kyp = agentWorkflowStateService.latestArtifact(workflowId, AgentType.PRODUCT_EXPERT);
        return Map.of(
                "kycArtifactId", kyc == null ? "" : kyc.getArtifactId(),
                "marketArtifactId", market == null ? "" : market.getArtifactId(),
                "kypArtifactId", kyp == null ? "" : kyp.getArtifactId());
    }

    private boolean isCurrentSuccess(
            AgentSucceededEvent event, WorkflowState workflow, AgentState state, AgentArtifact artifact) {
        return workflow != null && workflow.getWorkflowStatus() == WorkflowStatus.RUNNING
                && state != null && state.getAgentStatus() == AgentStatus.SUCCESS
                && event.executionId().equals(state.getExecutionId())
                && artifact != null
                && event.workflowId().equals(artifact.getWorkflowId())
                && event.agentStateId().equals(artifact.getAgentStateId())
                && artifact.getAgentType() == event.agentType()
                && event.executionId().equals(artifact.getExecutionId());
    }

    private boolean isCurrentFailure(AgentFailedEvent event, WorkflowState workflow, AgentState state) {
        return workflow != null && workflow.getWorkflowStatus() == WorkflowStatus.RUNNING
                && state != null && state.getAgentStatus() == AgentStatus.FAILED
                && event.executionId().equals(state.getExecutionId());
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
