package com.privatebank.agent.adapter.workflow;

import com.privatebank.agent.application.kyc.KycWorkflowExecutionService;
import com.privatebank.agent.application.kyc.KycRuntimeSupplement;
import com.privatebank.business.service.workflow.KycRegenerationRequestedEvent;
import com.privatebank.business.service.workflow.WorkflowCreatedEvent;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class KycWorkflowListenerTest {

    @Test
    void startsKycForBothInitialAndRegenerationEvents() {
        KycWorkflowExecutionService executionService = mock(KycWorkflowExecutionService.class);
        KycWorkflowListener listener = new KycWorkflowListener(executionService);
        KycRuntimeSupplement supplement = new KycRuntimeSupplement(
                "补充流动性安排", java.util.List.of("流动性"));

        listener.onWorkflowCreated(new WorkflowCreatedEvent("WF-initial"));
        listener.onKycRegenerationRequested(new KycRegenerationRequestedEvent(
                "WF-regenerated", "补充流动性安排", java.util.List.of("流动性")));

        verify(executionService).execute("WF-initial");
        verify(executionService).execute("WF-regenerated", supplement);
    }
}
