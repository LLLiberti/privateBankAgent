package com.privatebank.business.service.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.agent.domain.event.AgentFailedEvent;
import com.privatebank.agent.infrastructure.workflow.AgentWorkflowStateService;
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
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkflowAgentResultListenerTest {

    @Test
    void transitionsToWaitingInputOnlyAfterCurrentKycSuccess() {
        WorkflowStateMapper workflowMapper = mock(WorkflowStateMapper.class);
        AgentStateMapper agentStateMapper = mock(AgentStateMapper.class);
        AgentArtifactMapper artifactMapper = mock(AgentArtifactMapper.class);
        WorkflowEventHub eventHub = mock(WorkflowEventHub.class);
        WorkflowAgentResultListener listener = new WorkflowAgentResultListener(
                workflowMapper,
                agentStateMapper,
                artifactMapper,
                mock(AgentWorkflowStateService.class),
                eventHub,
                mock(ApplicationEventPublisher.class),
                new ObjectMapper().findAndRegisterModules());
        WorkflowState workflow = workflow(WorkflowStatus.RUNNING);
        AgentState state = agentState(AgentStatus.SUCCESS, "EXE-1");
        AgentArtifact artifact = artifact("ART-1", "EXE-1");
        when(workflowMapper.selectById("WF-1")).thenReturn(workflow);
        when(agentStateMapper.selectById("AS-1")).thenReturn(state);
        when(artifactMapper.selectById("ART-1")).thenReturn(artifact);
        when(workflowMapper.updateById(any(WorkflowState.class))).thenReturn(1);

        listener.onAgentSucceeded(new AgentSucceededEvent(
                "WF-1", "AS-1", AgentType.CUSTOMER_INSIGHT, "EXE-1", "ART-1"));

        assertThat(workflow.getWorkflowStatus()).isEqualTo(WorkflowStatus.WAITING_INPUT);
        assertThat(workflow.getFinishTime()).isNull();
        verify(eventHub).publish(eq("WF-1"), eq("KYC_ANALYSIS_COMPLETED"), any());
    }

    @Test
    void transitionsToFailedOnlyAfterCurrentKycFailure() {
        WorkflowStateMapper workflowMapper = mock(WorkflowStateMapper.class);
        AgentStateMapper agentStateMapper = mock(AgentStateMapper.class);
        AgentArtifactMapper artifactMapper = mock(AgentArtifactMapper.class);
        WorkflowEventHub eventHub = mock(WorkflowEventHub.class);
        WorkflowAgentResultListener listener = new WorkflowAgentResultListener(
                workflowMapper,
                agentStateMapper,
                artifactMapper,
                mock(AgentWorkflowStateService.class),
                eventHub,
                mock(ApplicationEventPublisher.class),
                new ObjectMapper().findAndRegisterModules());
        WorkflowState workflow = workflow(WorkflowStatus.RUNNING);
        AgentState state = agentState(AgentStatus.FAILED, "EXE-1");
        state.setErrorMessage("Model invocation failed");
        when(workflowMapper.selectById("WF-1")).thenReturn(workflow);
        when(agentStateMapper.selectById("AS-1")).thenReturn(state);
        when(workflowMapper.updateById(any(WorkflowState.class))).thenReturn(1);

        listener.onAgentFailed(new AgentFailedEvent(
                "WF-1", "AS-1", AgentType.CUSTOMER_INSIGHT, "EXE-1", "MODEL_CALL_FAILED"));

        assertThat(workflow.getWorkflowStatus()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(workflow.getErrorCode()).isEqualTo("MODEL_CALL_FAILED");
        assertThat(workflow.getErrorMessage()).isEqualTo("Model invocation failed");
        assertThat(workflow.getFinishTime()).isNotNull();
        verify(eventHub).publish(eq("WF-1"), eq("KYC_ANALYSIS_FAILED"), any());
    }

    @Test
    void dropsLateKycSuccessAfterWorkflowWasCanceled() {
        WorkflowStateMapper workflowMapper = mock(WorkflowStateMapper.class);
        AgentStateMapper agentStateMapper = mock(AgentStateMapper.class);
        AgentArtifactMapper artifactMapper = mock(AgentArtifactMapper.class);
        WorkflowEventHub eventHub = mock(WorkflowEventHub.class);
        WorkflowAgentResultListener listener = new WorkflowAgentResultListener(
                workflowMapper,
                agentStateMapper,
                artifactMapper,
                mock(AgentWorkflowStateService.class),
                eventHub,
                mock(ApplicationEventPublisher.class),
                new ObjectMapper().findAndRegisterModules());
        when(workflowMapper.selectById("WF-1")).thenReturn(workflow(WorkflowStatus.CANCELED));
        when(agentStateMapper.selectById("AS-1")).thenReturn(agentState(AgentStatus.SUCCESS, "EXE-1"));
        when(artifactMapper.selectById("ART-1")).thenReturn(artifact("ART-1", "EXE-1"));

        listener.onAgentSucceeded(new AgentSucceededEvent(
                "WF-1", "AS-1", AgentType.CUSTOMER_INSIGHT, "EXE-1", "ART-1"));

        verify(workflowMapper, never()).updateById(any(WorkflowState.class));
        verifyNoInteractions(eventHub);
    }

    private WorkflowState workflow(WorkflowStatus status) {
        WorkflowState workflow = new WorkflowState();
        workflow.setWorkflowId("WF-1");
        workflow.setWorkflowStatus(status);
        workflow.setVersion(0L);
        return workflow;
    }

    private AgentState agentState(AgentStatus status, String executionId) {
        AgentState state = new AgentState();
        state.setAgentStateId("AS-1");
        state.setWorkflowId("WF-1");
        state.setAgentType(AgentType.CUSTOMER_INSIGHT);
        state.setAgentStatus(status);
        state.setExecutionId(executionId);
        state.setVersion(0L);
        return state;
    }

    private AgentArtifact artifact(String artifactId, String executionId) {
        AgentArtifact artifact = new AgentArtifact();
        artifact.setArtifactId(artifactId);
        artifact.setWorkflowId("WF-1");
        artifact.setAgentStateId("AS-1");
        artifact.setAgentType(AgentType.CUSTOMER_INSIGHT);
        artifact.setExecutionId(executionId);
        return artifact;
    }
}
