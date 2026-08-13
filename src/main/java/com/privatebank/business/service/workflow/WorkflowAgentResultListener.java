package com.privatebank.business.service.workflow;

import com.privatebank.agent.domain.event.AgentFailedEvent;
import com.privatebank.agent.domain.event.AgentSucceededEvent;
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
    private final WorkflowEventHub eventHub;

    @Transactional
    @EventListener
    public void onAgentSucceeded(AgentSucceededEvent event) {
        if (event.agentType() != AgentType.CUSTOMER_INSIGHT) {
            return;
        }
        WorkflowState workflow = workflowMapper.selectById(event.workflowId());
        AgentState state = agentStateMapper.selectById(event.agentStateId());
        AgentArtifact artifact = artifactMapper.selectById(event.artifactId());
        if (!isCurrentKycSuccess(event, workflow, state, artifact)) {
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

    @Transactional
    @EventListener
    public void onAgentFailed(AgentFailedEvent event) {
        if (event.agentType() != AgentType.CUSTOMER_INSIGHT) {
            return;
        }
        WorkflowState workflow = workflowMapper.selectById(event.workflowId());
        AgentState state = agentStateMapper.selectById(event.agentStateId());
        if (!isCurrentKycFailure(event, workflow, state)) {
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

    private boolean isCurrentKycSuccess(
            AgentSucceededEvent event, WorkflowState workflow, AgentState state, AgentArtifact artifact) {
        return workflow != null && workflow.getWorkflowStatus() == WorkflowStatus.RUNNING
                && state != null && state.getAgentStatus() == AgentStatus.SUCCESS
                && event.executionId().equals(state.getExecutionId())
                && artifact != null
                && event.workflowId().equals(artifact.getWorkflowId())
                && event.agentStateId().equals(artifact.getAgentStateId())
                && artifact.getAgentType() == AgentType.CUSTOMER_INSIGHT
                && event.executionId().equals(artifact.getExecutionId());
    }

    private boolean isCurrentKycFailure(AgentFailedEvent event, WorkflowState workflow, AgentState state) {
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
