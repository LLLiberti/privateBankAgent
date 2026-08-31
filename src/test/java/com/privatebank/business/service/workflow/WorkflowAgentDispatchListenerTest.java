package com.privatebank.business.service.workflow;

import com.privatebank.business.dto.workflow.KycQaItem;
import com.privatebank.business.enums.workflow.AgentType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class WorkflowAgentDispatchListenerTest {

    @Test
    void workflowEventsAreTranslatedIntoAgentDispatchIntents() {
        WorkflowAgentDispatchService dispatchService = mock(WorkflowAgentDispatchService.class);
        WorkflowAgentDispatchListener listener = new WorkflowAgentDispatchListener(dispatchService);
        KycQaItem qa = new KycQaItem("Q-1", "question", "answer");

        listener.onWorkflowCreated(new WorkflowCreatedEvent("WF-1"));
        listener.onKycRegenerationRequested(new KycRegenerationRequestedEvent(
                "WF-1", "description", List.of("confirmed"), List.of(qa)));
        listener.onDownstreamAgentsReady(new DownstreamAgentsReadyEvent(
                "WF-1", "ART-KYC", List.of(AgentType.MARKET_INSIGHT, AgentType.PRODUCT_EXPERT)));
        listener.onAgentDispatchRequested(new AgentDispatchRequestedEvent(
                "WF-1", AgentType.SOLUTION_DESIGN, Map.of("kycArtifactId", "ART-KYC")));

        ArgumentCaptor<AgentDispatchRequestedEvent> dispatched =
                ArgumentCaptor.forClass(AgentDispatchRequestedEvent.class);
        verify(dispatchService, times(5)).dispatch(dispatched.capture());
        assertThat(dispatched.getAllValues()).extracting(AgentDispatchRequestedEvent::agentType)
                .containsExactly(
                        AgentType.CUSTOMER_INSIGHT,
                        AgentType.CUSTOMER_INSIGHT,
                        AgentType.MARKET_INSIGHT,
                        AgentType.PRODUCT_EXPERT,
                        AgentType.SOLUTION_DESIGN);
        assertThat(dispatched.getAllValues().get(1).qaItems()).containsExactly(qa);
        assertThat(dispatched.getAllValues().get(2).inputArtifactIds())
                .containsEntry("kycArtifactId", "ART-KYC");
    }
}
