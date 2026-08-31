package com.privatebank.business.service.workflow;

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
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

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
    void persistsKycResultThenTransitionsWorkflowToWaitingInput() {
        Fixture fixture = fixture();
        AgentExecutionCompletedEvent event = completed(AgentType.CUSTOMER_INSIGHT);
        WorkflowState workflow = workflow(WorkflowStatus.RUNNING);
        AgentArtifact artifact = artifact("ART-KYC", AgentType.CUSTOMER_INSIGHT, "{}");
        when(fixture.stateService.complete(event)).thenReturn(Optional.of(
                new WorkflowAgentStateService.PersistedAgentResult(
                        workflow, state(AgentType.CUSTOMER_INSIGHT, AgentStatus.SUCCESS), artifact)));
        when(fixture.workflowMapper.updateById(any(WorkflowState.class))).thenReturn(1);

        fixture.listener.onAgentCompleted(event);

        assertThat(workflow.getWorkflowStatus()).isEqualTo(WorkflowStatus.WAITING_INPUT);
        verify(fixture.stateService).complete(event);
        verify(fixture.eventHub).publish(eq("WF-1"), eq("KYC_ANALYSIS_COMPLETED"), any());
    }

    @Test
    void persistsFailureThenFailsWorkflow() {
        Fixture fixture = fixture();
        AgentExecutionFailedEvent event = new AgentExecutionFailedEvent(
                "WF-1", "AS-CUSTOMER_INSIGHT", AgentType.CUSTOMER_INSIGHT,
                "EXE-1", "MODEL_CALL_FAILED", "model unavailable");
        WorkflowState workflow = workflow(WorkflowStatus.RUNNING);
        when(fixture.stateService.fail(event)).thenReturn(Optional.of(
                new WorkflowAgentStateService.FailedAgentResult(
                        workflow, state(AgentType.CUSTOMER_INSIGHT, AgentStatus.FAILED))));
        when(fixture.workflowMapper.updateById(any(WorkflowState.class))).thenReturn(1);

        fixture.listener.onAgentFailed(event);

        assertThat(workflow.getWorkflowStatus()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(workflow.getErrorCode()).isEqualTo("MODEL_CALL_FAILED");
        assertThat(workflow.getErrorMessage()).isEqualTo("model unavailable");
        verify(fixture.eventHub).publish(eq("WF-1"), eq("KYC_ANALYSIS_FAILED"), any());
    }

    @Test
    void ignoresLateResultRejectedByTheStateOwner() {
        Fixture fixture = fixture();
        AgentExecutionCompletedEvent event = completed(AgentType.CUSTOMER_INSIGHT);
        when(fixture.stateService.complete(event)).thenReturn(Optional.empty());

        fixture.listener.onAgentCompleted(event);

        verify(fixture.workflowMapper, never()).updateById(any(WorkflowState.class));
        verifyNoInteractions(fixture.eventHub, fixture.eventPublisher);
    }

    private Fixture fixture() {
        WorkflowStateMapper workflowMapper = mock(WorkflowStateMapper.class);
        WorkflowAgentStateService stateService = mock(WorkflowAgentStateService.class);
        WorkflowEventHub eventHub = mock(WorkflowEventHub.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        return new Fixture(
                workflowMapper, stateService, eventHub, eventPublisher,
                new WorkflowAgentResultListener(
                        workflowMapper, stateService, eventHub, eventPublisher,
                        new ObjectMapper().findAndRegisterModules()));
    }

    private AgentExecutionCompletedEvent completed(AgentType type) {
        return new AgentExecutionCompletedEvent(
                "WF-1", "AS-" + type, type, "EXE-1", "{}", null, 0);
    }

    private WorkflowState workflow(WorkflowStatus status) {
        WorkflowState workflow = new WorkflowState();
        workflow.setWorkflowId("WF-1");
        workflow.setWorkflowStatus(status);
        workflow.setVersion(0L);
        return workflow;
    }

    private AgentState state(AgentType type, AgentStatus status) {
        AgentState state = new AgentState();
        state.setAgentStateId("AS-" + type);
        state.setWorkflowId("WF-1");
        state.setAgentType(type);
        state.setAgentStatus(status);
        state.setExecutionId("EXE-1");
        return state;
    }

    private AgentArtifact artifact(String id, AgentType type, String result) {
        AgentArtifact artifact = new AgentArtifact();
        artifact.setArtifactId(id);
        artifact.setWorkflowId("WF-1");
        artifact.setAgentType(type);
        artifact.setResult(result);
        return artifact;
    }

    private record Fixture(
            WorkflowStateMapper workflowMapper,
            WorkflowAgentStateService stateService,
            WorkflowEventHub eventHub,
            ApplicationEventPublisher eventPublisher,
            WorkflowAgentResultListener listener) {
    }
}
