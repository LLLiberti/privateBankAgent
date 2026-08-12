package com.privatebank.agent.adapter.workflow;

import com.privatebank.agent.application.kyc.KycWorkflowExecutionService;
import com.privatebank.agent.application.kyc.KycRuntimeSupplement;
import com.privatebank.agent.application.kyc.KycRuntimeSupplementProjector;
import com.privatebank.business.service.workflow.KycRegenerationRequestedEvent;
import com.privatebank.business.service.workflow.WorkflowCreatedEvent;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KycWorkflowListenerTest {

    @Test
    void startsKycForBothInitialAndRegenerationEvents() {
        KycWorkflowExecutionService executionService = mock(KycWorkflowExecutionService.class);
        KycRuntimeSupplementProjector supplementProjector = mock(KycRuntimeSupplementProjector.class);
        KycWorkflowListener listener = new KycWorkflowListener(executionService, supplementProjector);
        KycRuntimeSupplement supplement = new KycRuntimeSupplement(java.util.Set.of("LIQUIDITY_NEED"));
        when(supplementProjector.project("补充流动性安排", java.util.List.of("流动性"))).thenReturn(supplement);

        listener.onWorkflowCreated(new WorkflowCreatedEvent("WF-initial"));
        listener.onKycRegenerationRequested(new KycRegenerationRequestedEvent(
                "WF-regenerated", "补充流动性安排", java.util.List.of("流动性")));

        verify(executionService).execute("WF-initial");
        verify(executionService).execute("WF-regenerated", supplement);
    }
}
