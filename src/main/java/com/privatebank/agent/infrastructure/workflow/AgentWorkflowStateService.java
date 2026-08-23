package com.privatebank.agent.infrastructure.workflow;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.privatebank.agent.application.runtime.AgentExecutionClaim;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Persists non-KYC Agent execution state and artifacts.  It never advances the
 * workflow itself; the workflow listener owns state transitions.
 */
@Service
@RequiredArgsConstructor
public class AgentWorkflowStateService {

    private final WorkflowStateMapper workflowMapper;
    private final AgentStateMapper agentStateMapper;
    private final AgentArtifactMapper artifactMapper;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public Optional<AgentExecutionClaim> claim(String workflowId, AgentType agentType) {
        WorkflowState workflow = workflowMapper.selectById(workflowId);
        if (workflow == null || workflow.getWorkflowStatus().isTerminal()) {
            return Optional.empty();
        }
        AgentState state = agentState(workflowId, agentType);
        if (state == null || state.getAgentStatus() != AgentStatus.READY) {
            return Optional.empty();
        }
        LocalDateTime now = LocalDateTime.now();
        String executionId = "EXE-" + UUID.randomUUID();
        state.setAgentStatus(AgentStatus.RUNNING);
        state.setExecutionId(executionId);
        state.setErrorCode(null);
        state.setErrorMessage(null);
        state.setStartTime(now);
        state.setFinishTime(null);
        requireUpdated(agentStateMapper.updateById(state), agentType + " Agent 状态已被并发修改");

        if (workflow.getWorkflowStatus() != WorkflowStatus.RUNNING
                || workflow.getErrorCode() != null
                || workflow.getErrorMessage() != null
                || workflow.getStartTime() == null) {
            workflow.setWorkflowStatus(WorkflowStatus.RUNNING);
            workflow.setErrorCode(null);
            workflow.setErrorMessage(null);
            if (workflow.getStartTime() == null) {
                workflow.setStartTime(now);
            }
            workflow.setUpdatedAt(now);
            requireUpdated(workflowMapper.updateById(workflow), "工作流状态已被并发修改");
        }
        return Optional.of(new AgentExecutionClaim(
                workflowId, state.getAgentStateId(), agentType, executionId, workflow.getCreatedBy()));
    }

    @Transactional
    public boolean complete(AgentExecutionClaim claim, String resultJson, String complianceResult) {
        WorkflowState workflow = workflowMapper.selectById(claim.workflowId());
        AgentState state = agentStateMapper.selectById(claim.agentStateId());
        if (!isCurrentClaim(workflow, state, claim)) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        AgentArtifact artifact = new AgentArtifact();
        artifact.setArtifactId("ART-" + UUID.randomUUID());
        artifact.setWorkflowId(claim.workflowId());
        artifact.setAgentStateId(state.getAgentStateId());
        artifact.setAgentType(claim.agentType());
        artifact.setExecutionId(claim.executionId());
        artifact.setResult(resultJson);
        artifact.setComplianceResult(complianceResult);
        artifact.setVersion(nextArtifactVersion(claim.workflowId(), claim.agentType()));
        artifact.setCreateTime(now);
        requireUpdated(artifactMapper.insert(artifact), claim.agentType() + " 产物未能保存");

        state.setAgentStatus(AgentStatus.SUCCESS);
        state.setFinishTime(now);
        requireUpdated(agentStateMapper.updateById(state), claim.agentType() + " Agent 状态已被并发修改");

        afterCommit(() -> eventPublisher.publishEvent(new AgentSucceededEvent(
                claim.workflowId(), state.getAgentStateId(), claim.agentType(),
                claim.executionId(), artifact.getArtifactId())));
        return true;
    }

    @Transactional
    public boolean fail(AgentExecutionClaim claim, String errorCode, String errorMessage) {
        WorkflowState workflow = workflowMapper.selectById(claim.workflowId());
        AgentState state = agentStateMapper.selectById(claim.agentStateId());
        if (!isCurrentClaim(workflow, state, claim)) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        state.setAgentStatus(AgentStatus.FAILED);
        state.setErrorCode(errorCode);
        state.setErrorMessage(errorMessage);
        state.setFinishTime(now);
        requireUpdated(agentStateMapper.updateById(state), claim.agentType() + " Agent 状态已被并发修改");

        afterCommit(() -> eventPublisher.publishEvent(new AgentFailedEvent(
                claim.workflowId(), state.getAgentStateId(), claim.agentType(),
                claim.executionId(), errorCode)));
        return true;
    }

    @Transactional
    public void ready(String workflowId, AgentType agentType) {
        AgentState state = agentState(workflowId, agentType);
        if (state == null) {
            throw new IllegalStateException("Agent状态不存在: " + agentType);
        }
        state.setAgentStatus(AgentStatus.READY);
        state.setErrorCode(null);
        state.setErrorMessage(null);
        state.setStartTime(null);
        state.setFinishTime(null);
        requireUpdated(agentStateMapper.updateById(state), agentType + " Agent 状态已被并发修改");
    }

    public AgentState agentState(String workflowId, AgentType agentType) {
        return agentStateMapper.selectOne(Wrappers.<AgentState>lambdaQuery()
                .eq(AgentState::getWorkflowId, workflowId)
                .eq(AgentState::getAgentType, agentType));
    }

    public AgentArtifact latestArtifact(String workflowId, AgentType agentType) {
        return artifactMapper.selectOne(Wrappers.<AgentArtifact>lambdaQuery()
                .eq(AgentArtifact::getWorkflowId, workflowId)
                .eq(AgentArtifact::getAgentType, agentType)
                .orderByDesc(AgentArtifact::getVersion)
                .last("LIMIT 1"));
    }

    private boolean isCurrentClaim(WorkflowState workflow, AgentState state, AgentExecutionClaim claim) {
        return workflow != null && workflow.getWorkflowStatus() == WorkflowStatus.RUNNING
                && state != null && state.getAgentStatus() == AgentStatus.RUNNING
                && claim.agentStateId().equals(state.getAgentStateId())
                && claim.executionId().equals(state.getExecutionId());
    }

    private int nextArtifactVersion(String workflowId, AgentType agentType) {
        AgentArtifact latest = latestArtifact(workflowId, agentType);
        return latest == null ? 1 : latest.getVersion() + 1;
    }

    private void requireUpdated(int affected, String message) {
        if (affected != 1) {
            throw new AgentWorkflowStateConflictException(message);
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
