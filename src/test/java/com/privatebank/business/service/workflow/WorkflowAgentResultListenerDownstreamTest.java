package com.privatebank.business.service.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.agent.application.runtime.AgentExecutionCompletedEvent;
import com.privatebank.business.entity.workflow.AgentArtifact;
import com.privatebank.business.entity.workflow.AgentState;
import com.privatebank.business.entity.workflow.WorkflowState;
import com.privatebank.business.enums.workflow.AgentStatus;
import com.privatebank.business.enums.workflow.AgentType;
import com.privatebank.business.enums.workflow.WorkflowStatus;
import com.privatebank.business.mapper.workflow.WorkflowStateMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkflowAgentResultListenerDownstreamTest {

    @Test
    void waitsForSecondParallelAgentBeforeReleasingCfs() {
        Fixture fixture = fixture();
        AgentExecutionCompletedEvent event = completed(AgentType.MARKET_INSIGHT);
        WorkflowState workflow = workflow();
        AgentArtifact marketArtifact = artifact("ART-MARKET", AgentType.MARKET_INSIGHT, "{}");
        when(fixture.stateService.complete(event)).thenReturn(Optional.of(result(
                workflow, AgentType.MARKET_INSIGHT, marketArtifact)));
        when(fixture.stateService.agentState("WF-1", AgentType.MARKET_INSIGHT))
                .thenReturn(state(AgentType.MARKET_INSIGHT, AgentStatus.SUCCESS));
        when(fixture.stateService.agentState("WF-1", AgentType.PRODUCT_EXPERT))
                .thenReturn(state(AgentType.PRODUCT_EXPERT, AgentStatus.RUNNING));

        fixture.listener.onAgentCompleted(event);

        verify(fixture.stateService, never()).ready("WF-1", AgentType.SOLUTION_DESIGN);
        verifyNoInteractions(fixture.eventPublisher);
    }

    @Test
    void releasesCfsWhenBothParallelAgentsSucceed() {
        Fixture fixture = fixture();
        AgentExecutionCompletedEvent event = completed(AgentType.MARKET_INSIGHT);
        WorkflowState workflow = workflow();
        AgentArtifact marketArtifact = artifact("ART-MARKET", AgentType.MARKET_INSIGHT, "{}");
        when(fixture.stateService.complete(event)).thenReturn(Optional.of(result(
                workflow, AgentType.MARKET_INSIGHT, marketArtifact)));
        when(fixture.stateService.agentState("WF-1", AgentType.MARKET_INSIGHT))
                .thenReturn(state(AgentType.MARKET_INSIGHT, AgentStatus.SUCCESS));
        when(fixture.stateService.agentState("WF-1", AgentType.PRODUCT_EXPERT))
                .thenReturn(state(AgentType.PRODUCT_EXPERT, AgentStatus.SUCCESS));
        when(fixture.stateService.latestArtifact("WF-1", AgentType.CUSTOMER_INSIGHT))
                .thenReturn(artifact("ART-KYC", AgentType.CUSTOMER_INSIGHT, "{}"));
        when(fixture.stateService.latestArtifact("WF-1", AgentType.MARKET_INSIGHT)).thenReturn(marketArtifact);
        when(fixture.stateService.latestArtifact("WF-1", AgentType.PRODUCT_EXPERT))
                .thenReturn(artifact("ART-PRODUCT", AgentType.PRODUCT_EXPERT, "{}"));

        fixture.listener.onAgentCompleted(event);

        verify(fixture.stateService).ready("WF-1", AgentType.SOLUTION_DESIGN);
        AgentDispatchRequestedEvent dispatch = publishedDispatch(fixture);
        assertThat(dispatch.agentType()).isEqualTo(AgentType.SOLUTION_DESIGN);
        assertThat(dispatch.inputArtifactIds()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "kycArtifactId", "ART-KYC",
                "marketArtifactId", "ART-MARKET",
                "kypArtifactId", "ART-PRODUCT"));
    }

    @Test
    void releasesComplianceAfterCfsSucceeds() {
        Fixture fixture = fixture();
        AgentExecutionCompletedEvent event = completed(AgentType.SOLUTION_DESIGN);
        AgentArtifact cfs = artifact("ART-CFS", AgentType.SOLUTION_DESIGN, "{}");
        when(fixture.stateService.complete(event)).thenReturn(Optional.of(result(
                workflow(), AgentType.SOLUTION_DESIGN, cfs)));

        fixture.listener.onAgentCompleted(event);

        verify(fixture.stateService).ready("WF-1", AgentType.COMPLIANCE_CHECK);
        assertThat(publishedDispatch(fixture).inputArtifactIds())
                .containsEntry("cfsArtifactId", "ART-CFS");
    }

    @Test
    void movesToWaitingReviewWhenCompliancePasses() {
        Fixture fixture = fixture();
        AgentExecutionCompletedEvent event = completed(AgentType.COMPLIANCE_CHECK);
        WorkflowState workflow = workflow();
        AgentArtifact compliance = artifact(
                "ART-COMPLIANCE", AgentType.COMPLIANCE_CHECK, "{\"cfsArtifactRef\":\"ART-CFS\"}");
        compliance.setComplianceResult("PASS");
        when(fixture.stateService.complete(event)).thenReturn(Optional.of(result(
                workflow, AgentType.COMPLIANCE_CHECK, compliance)));
        when(fixture.workflowMapper.updateById(any(WorkflowState.class))).thenReturn(1);

        fixture.listener.onAgentCompleted(event);

        assertThat(workflow.getWorkflowStatus()).isEqualTo(WorkflowStatus.WAITING_REVIEW);
        verify(fixture.eventHub).publish(eq("WF-1"), eq("COMPLIANCE_PASSED"), any());
        verifyNoInteractions(fixture.eventPublisher);
    }

    @Test
    void reopensCfsWhenComplianceRejects() {
        Fixture fixture = fixture();
        AgentExecutionCompletedEvent event = completed(AgentType.COMPLIANCE_CHECK);
        WorkflowState workflow = workflow();
        AgentArtifact compliance = artifact("ART-COMPLIANCE", AgentType.COMPLIANCE_CHECK, "{}");
        compliance.setComplianceResult("REJECT");
        when(fixture.stateService.complete(event)).thenReturn(Optional.of(result(
                workflow, AgentType.COMPLIANCE_CHECK, compliance)));
        when(fixture.workflowMapper.updateById(any(WorkflowState.class))).thenReturn(1);
        when(fixture.stateService.latestArtifact("WF-1", AgentType.CUSTOMER_INSIGHT))
                .thenReturn(artifact("ART-KYC", AgentType.CUSTOMER_INSIGHT, "{}"));
        when(fixture.stateService.latestArtifact("WF-1", AgentType.MARKET_INSIGHT))
                .thenReturn(artifact("ART-MARKET", AgentType.MARKET_INSIGHT, "{}"));
        when(fixture.stateService.latestArtifact("WF-1", AgentType.PRODUCT_EXPERT))
                .thenReturn(artifact("ART-PRODUCT", AgentType.PRODUCT_EXPERT, "{}"));

        fixture.listener.onAgentCompleted(event);

        assertThat(workflow.getWorkflowStatus()).isEqualTo(WorkflowStatus.RUNNING);
        verify(fixture.stateService).ready("WF-1", AgentType.SOLUTION_DESIGN);
        assertThat(publishedDispatch(fixture).agentType()).isEqualTo(AgentType.SOLUTION_DESIGN);
    }

    private AgentDispatchRequestedEvent publishedDispatch(Fixture fixture) {
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(fixture.eventPublisher).publishEvent(event.capture());
        return (AgentDispatchRequestedEvent) event.getValue();
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

    private WorkflowAgentStateService.PersistedAgentResult result(
            WorkflowState workflow, AgentType type, AgentArtifact artifact) {
        return new WorkflowAgentStateService.PersistedAgentResult(
                workflow, state(type, AgentStatus.SUCCESS), artifact);
    }

    private WorkflowState workflow() {
        WorkflowState workflow = new WorkflowState();
        workflow.setWorkflowId("WF-1");
        workflow.setWorkflowStatus(WorkflowStatus.RUNNING);
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
