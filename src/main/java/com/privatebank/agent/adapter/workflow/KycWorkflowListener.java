package com.privatebank.agent.adapter.workflow;

import com.privatebank.agent.application.kyc.KycWorkflowExecutionService;
import com.privatebank.agent.application.kyc.KycRuntimeSupplement;
import com.privatebank.agent.infrastructure.kyc.KycAsyncConfiguration;
import com.privatebank.business.service.workflow.KycRegenerationRequestedEvent;
import com.privatebank.business.service.workflow.WorkflowCreatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class KycWorkflowListener {

    private final KycWorkflowExecutionService kycWorkflowExecutionService;

    @Async(KycAsyncConfiguration.KYC_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onWorkflowCreated(WorkflowCreatedEvent event) {
        kycWorkflowExecutionService.execute(event.workflowId());
    }

    @Async(KycAsyncConfiguration.KYC_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onKycRegenerationRequested(KycRegenerationRequestedEvent event) {
        kycWorkflowExecutionService.execute(event.workflowId(),
                new KycRuntimeSupplement(
                        event.managerDescription(),
                        event.managerConfirmedItems(),
                        event.qaItems()));
    }
}
