package com.privatebank.business.service.workflow;

import com.privatebank.agent.application.runtime.AgentExecutionFailedEvent;
import com.privatebank.agent.application.runtime.AgentExecutionRequestedEvent;
import com.privatebank.business.entity.workflow.AgentArtifact;
import com.privatebank.business.enums.workflow.AgentType;
import com.privatebank.business.mapper.workflow.AgentArtifactMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowAgentDispatchServiceTest {

    @Test
    void claimsAndLoadsArtifactBeforeDispatchingAgentExecution() {
        WorkflowAgentStateService stateService = mock(WorkflowAgentStateService.class);
        AgentArtifactMapper artifactMapper = mock(AgentArtifactMapper.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        WorkflowAgentDispatchService service = new WorkflowAgentDispatchService(stateService, artifactMapper, publisher);
        when(stateService.claim("WF-1", AgentType.MARKET_INSIGHT)).thenReturn(Optional.of(
                new WorkflowAgentExecutionClaim(
                        "WF-1", "AS-1", AgentType.MARKET_INSIGHT, "EXE-1", "USER-1", 100L)));
        AgentArtifact artifact = new AgentArtifact();
        artifact.setWorkflowId("WF-1");
        artifact.setResult("{\"analysis\":{}}");
        when(artifactMapper.selectById("ART-KYC")).thenReturn(artifact);

        service.dispatch(new AgentDispatchRequestedEvent(
                "WF-1", AgentType.MARKET_INSIGHT, Map.of("kycArtifactId", "ART-KYC")));

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(event.capture());
        AgentExecutionRequestedEvent requested = (AgentExecutionRequestedEvent) event.getValue();
        assertThat(requested.executionId()).isEqualTo("EXE-1");
        assertThat(requested.inputArtifactResults())
                .containsEntry("kycArtifactId", "{\"analysis\":{}}");
    }

    @Test
    void reportsInvalidArtifactThroughTheSameResultChannel() {
        WorkflowAgentStateService stateService = mock(WorkflowAgentStateService.class);
        AgentArtifactMapper artifactMapper = mock(AgentArtifactMapper.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        WorkflowAgentDispatchService service = new WorkflowAgentDispatchService(stateService, artifactMapper, publisher);
        when(stateService.claim("WF-1", AgentType.MARKET_INSIGHT)).thenReturn(Optional.of(
                new WorkflowAgentExecutionClaim(
                        "WF-1", "AS-1", AgentType.MARKET_INSIGHT, "EXE-1", "USER-1", 100L)));

        service.dispatch(new AgentDispatchRequestedEvent(
                "WF-1", AgentType.MARKET_INSIGHT, Map.of("kycArtifactId", "ART-MISSING")));

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(event.capture());
        AgentExecutionFailedEvent failed = (AgentExecutionFailedEvent) event.getValue();
        assertThat(failed.errorCode()).isEqualTo("AGENT_INPUT_ARTIFACT_INVALID");
    }
}
