package com.privatebank.agent.infrastructure.kyc;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.agent.application.kyc.KycExecutionClaim;
import com.privatebank.agent.application.kyc.KycWorkflowStateConflictException;
import com.privatebank.agent.domain.event.AgentFailedEvent;
import com.privatebank.agent.domain.event.AgentSucceededEvent;
import com.privatebank.agent.domain.kyc.KycGenerationResult;
import com.privatebank.agent.domain.kyc.KycMaskedInput;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persists KYC Agent execution state and its artifact.  It never advances the
 * workflow after an Agent outcome; the workflow listener owns that transition.
 */
@Service
@RequiredArgsConstructor
public class KycWorkflowStateService {

    private final WorkflowStateMapper workflowMapper;
    private final AgentStateMapper agentStateMapper;
    private final AgentArtifactMapper artifactMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @Transactional
    public Optional<KycExecutionClaim> claim(String workflowId) {
        WorkflowState workflow = workflowMapper.selectById(workflowId);
        if (workflow == null || workflow.getWorkflowStatus().isTerminal()) {
            return Optional.empty();
        }
        AgentState state = customerInsightState(workflowId);
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
        requireUpdated(agentStateMapper.updateById(state), "KYC Agent 状态已被并发修改");

        workflow.setWorkflowStatus(WorkflowStatus.RUNNING);
        workflow.setErrorCode(null);
        workflow.setErrorMessage(null);
        if (workflow.getStartTime() == null) {
            workflow.setStartTime(now);
        }
        workflow.setUpdatedAt(now);
        requireUpdated(workflowMapper.updateById(workflow), "工作流状态已被并发修改");
        return Optional.of(new KycExecutionClaim(
                workflowId, workflow.getPersonId(), executionId, workflow.getCreatedBy()));
    }

    @Transactional
    public boolean complete(KycExecutionClaim claim, KycMaskedInput input, KycGenerationResult result) {
        WorkflowState workflow = workflowMapper.selectById(claim.workflowId());
        AgentState state = customerInsightState(claim.workflowId());
        if (!isCurrentClaim(workflow, state, claim)) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        AgentArtifact artifact = new AgentArtifact();
        artifact.setArtifactId("ART-" + UUID.randomUUID());
        artifact.setWorkflowId(claim.workflowId());
        artifact.setAgentStateId(state.getAgentStateId());
        artifact.setAgentType(AgentType.CUSTOMER_INSIGHT);
        artifact.setExecutionId(claim.executionId());
        artifact.setResult(artifactResult(input, result));
        artifact.setVersion(nextArtifactVersion(claim.workflowId()));
        artifact.setCreateTime(now);
        requireUpdated(artifactMapper.insert(artifact), "KYC 产物未能保存");

        state.setAgentStatus(AgentStatus.SUCCESS);
        state.setRetryCount(state.getRetryCount() + Math.max(0, result.attempts() - 1));
        state.setFinishTime(now);
        requireUpdated(agentStateMapper.updateById(state), "KYC Agent 状态已被并发修改");

        afterCommit(() -> eventPublisher.publishEvent(new AgentSucceededEvent(
                claim.workflowId(), state.getAgentStateId(), AgentType.CUSTOMER_INSIGHT,
                claim.executionId(), artifact.getArtifactId())));
        return true;
    }

    @Transactional
    public boolean fail(KycExecutionClaim claim, String errorCode, String errorMessage) {
        WorkflowState workflow = workflowMapper.selectById(claim.workflowId());
        AgentState state = customerInsightState(claim.workflowId());
        if (!isCurrentClaim(workflow, state, claim)) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        state.setAgentStatus(AgentStatus.FAILED);
        state.setErrorCode(errorCode);
        state.setErrorMessage(errorMessage);
        state.setFinishTime(now);
        requireUpdated(agentStateMapper.updateById(state), "KYC Agent 状态已被并发修改");

        afterCommit(() -> eventPublisher.publishEvent(new AgentFailedEvent(
                claim.workflowId(), state.getAgentStateId(), AgentType.CUSTOMER_INSIGHT,
                claim.executionId(), errorCode)));
        return true;
    }

    private AgentState customerInsightState(String workflowId) {
        return agentStateMapper.selectOne(Wrappers.<AgentState>lambdaQuery()
                .eq(AgentState::getWorkflowId, workflowId)
                .eq(AgentState::getAgentType, AgentType.CUSTOMER_INSIGHT));
    }

    private boolean isCurrentClaim(WorkflowState workflow, AgentState state, KycExecutionClaim claim) {
        return workflow != null && workflow.getWorkflowStatus() == WorkflowStatus.RUNNING
                && state != null && state.getAgentStatus() == AgentStatus.RUNNING
                && claim.executionId().equals(state.getExecutionId());
    }

    private int nextArtifactVersion(String workflowId) {
        AgentArtifact latest = artifactMapper.selectOne(Wrappers.<AgentArtifact>lambdaQuery()
                .eq(AgentArtifact::getWorkflowId, workflowId)
                .eq(AgentArtifact::getAgentType, AgentType.CUSTOMER_INSIGHT)
                .orderByDesc(AgentArtifact::getVersion)
                .last("LIMIT 1"));
        return latest == null ? 1 : latest.getVersion() + 1;
    }

    private String artifactResult(KycMaskedInput input, KycGenerationResult result) {
        try {
            JsonNode analysis = objectMapper.readTree(result.analysisJson());
            return objectMapper.writeValueAsString(Map.of(
                    "contractVersion", "kyc-result.v2",
                    "model", result.modelName(),
                    "modelAttempts", result.attempts(),
                    "maskingApplied", true,
                    "maskedInputSha256", input.sha256(),
                    "evidenceReferences", input.evidenceReferences(),
                    "aliasMappings", input.aliasMappings(),
                    "analysis", analysis));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("KYC 分析结果无法保存", exception);
        }
    }

    private void requireUpdated(int affected, String message) {
        if (affected != 1) {
            throw new KycWorkflowStateConflictException(message);
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
