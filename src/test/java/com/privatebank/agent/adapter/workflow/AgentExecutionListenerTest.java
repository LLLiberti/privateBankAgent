package com.privatebank.agent.adapter.workflow;

import com.privatebank.agent.application.downstream.DownstreamAgentExecutionService;
import com.privatebank.agent.application.kyc.KycWorkflowExecutionService;
import com.privatebank.agent.application.runtime.AgentExecutionRequestedEvent;
import com.privatebank.business.enums.workflow.AgentType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AgentExecutionListenerTest {

    @Test
    void routesCustomerInsightToKycCapability() {
        KycWorkflowExecutionService kyc = mock(KycWorkflowExecutionService.class);
        DownstreamAgentExecutionService downstream = mock(DownstreamAgentExecutionService.class);
        AgentExecutionListener listener = new AgentExecutionListener(kyc, downstream);
        AgentExecutionRequestedEvent event = event(AgentType.CUSTOMER_INSIGHT);

        listener.onAgentExecutionRequested(event);

        verify(kyc).execute(event);
        verify(downstream, never()).execute(event);
    }

    @Test
    void routesOtherTypesToDownstreamCapabilities() {
        KycWorkflowExecutionService kyc = mock(KycWorkflowExecutionService.class);
        DownstreamAgentExecutionService downstream = mock(DownstreamAgentExecutionService.class);
        AgentExecutionListener listener = new AgentExecutionListener(kyc, downstream);
        AgentExecutionRequestedEvent event = event(AgentType.SOLUTION_DESIGN);

        listener.onAgentExecutionRequested(event);

        verify(downstream).execute(event);
        verify(kyc, never()).execute(event);
    }

    private AgentExecutionRequestedEvent event(AgentType type) {
        return new AgentExecutionRequestedEvent(
                "WF-1", "AS-1", type, "EXE-1", "USER-1", 100L,
                Map.of(), Map.of(), null, List.of(), List.of());
    }
}
