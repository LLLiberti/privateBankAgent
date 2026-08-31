package com.privatebank.business.service.workflow;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.privatebank.agent.application.runtime.AgentExecutionCompletedEvent;
import com.privatebank.agent.application.runtime.AgentExecutionFailedEvent;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * The single owner of Agent execution state and Agent artifacts.
 */
@Service
@RequiredArgsConstructor
public class WorkflowAgentStateService {

    private final WorkflowStateMapper workflowMapper;
    private final AgentStateMapper agentStateMapper;
    private final AgentArtifactMapper artifactMapper;

    @Transactional
    public Optional<WorkflowAgentExecutionClaim> claim(String workflowId, AgentType agentType) {
        WorkflowState workflow = workflowMapper.selectById(workflowId);
        if (!canClaim(workflow, agentType)) {
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

        if (agentType == AgentType.CUSTOMER_INSIGHT) {
            workflow.setWorkflowStatus(WorkflowStatus.RUNNING);
            workflow.setErrorCode(null);
            workflow.setErrorMessage(null);
            workflow.setFinishTime(null);
            if (workflow.getStartTime() == null) {
                workflow.setStartTime(now);
            }
            workflow.setUpdatedAt(now);
            requireUpdated(workflowMapper.updateById(workflow), "工作流状态已被并发修改");
        }
        return Optional.of(new WorkflowAgentExecutionClaim(
                workflowId,
                state.getAgentStateId(),
                agentType,
                executionId,
                workflow.getCreatedBy(),
                workflow.getPersonId()));
    }

    @Transactional
    public Optional<PersistedAgentResult> complete(AgentExecutionCompletedEvent event) {
        WorkflowState workflow = workflowMapper.selectById(event.workflowId());
        AgentState state = agentStateMapper.selectById(event.agentStateId());
        if (!isCurrentClaim(workflow, state, event.agentType(), event.executionId())) {
            return Optional.empty();
        }
        LocalDateTime now = LocalDateTime.now();
        AgentArtifact artifact = new AgentArtifact();
        artifact.setArtifactId("ART-" + UUID.randomUUID());
        artifact.setWorkflowId(event.workflowId());
        artifact.setAgentStateId(state.getAgentStateId());
        artifact.setAgentType(event.agentType());
        artifact.setExecutionId(event.executionId());
        artifact.setResult(event.resultJson());
        artifact.setComplianceResult(event.complianceResult());
        artifact.setVersion(nextArtifactVersion(event.workflowId(), event.agentType()));
        artifact.setCreateTime(now);
        requireUpdated(artifactMapper.insert(artifact), event.agentType() + " 产物未能保存");

        state.setAgentStatus(AgentStatus.SUCCESS);
        state.setRetryCount(state.getRetryCount() + event.retryCountIncrement());
        state.setFinishTime(now);
        requireUpdated(agentStateMapper.updateById(state), event.agentType() + " Agent 状态已被并发修改");
        return Optional.of(new PersistedAgentResult(workflow, state, artifact));
    }

    @Transactional
    public Optional<FailedAgentResult> fail(AgentExecutionFailedEvent event) {
        WorkflowState workflow = workflowMapper.selectById(event.workflowId());
        AgentState state = agentStateMapper.selectById(event.agentStateId());
        if (!isCurrentClaim(workflow, state, event.agentType(), event.executionId())) {
            return Optional.empty();
        }
        state.setAgentStatus(AgentStatus.FAILED);
        state.setErrorCode(event.errorCode());
        state.setErrorMessage(event.errorMessage());
        state.setFinishTime(LocalDateTime.now());
        requireUpdated(agentStateMapper.updateById(state), event.agentType() + " Agent 状态已被并发修改");
        return Optional.of(new FailedAgentResult(workflow, state));
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

    private boolean canClaim(WorkflowState workflow, AgentType agentType) {
        if (workflow == null || workflow.getWorkflowStatus().isTerminal()) {
            return false;
        }
        return agentType == AgentType.CUSTOMER_INSIGHT
                ? workflow.getWorkflowStatus() == WorkflowStatus.CREATED
                        || workflow.getWorkflowStatus() == WorkflowStatus.RUNNING
                : workflow.getWorkflowStatus() == WorkflowStatus.RUNNING;
    }

    private boolean isCurrentClaim(
            WorkflowState workflow,
            AgentState state,
            AgentType agentType,
            String executionId) {
        return workflow != null
                && workflow.getWorkflowStatus() == WorkflowStatus.RUNNING
                && state != null
                && state.getAgentType() == agentType
                && state.getAgentStatus() == AgentStatus.RUNNING
                && executionId.equals(state.getExecutionId());
    }

    private int nextArtifactVersion(String workflowId, AgentType agentType) {
        AgentArtifact latest = latestArtifact(workflowId, agentType);
        return latest == null ? 1 : latest.getVersion() + 1;
    }

    private void requireUpdated(int affected, String message) {
        if (affected != 1) {
            throw new WorkflowAgentStateConflictException(message);
        }
    }

    public record PersistedAgentResult(
            WorkflowState workflow,
            AgentState state,
            AgentArtifact artifact) {
    }

    public record FailedAgentResult(
            WorkflowState workflow,
            AgentState state) {
    }
}
