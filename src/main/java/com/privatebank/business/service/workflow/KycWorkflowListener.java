package com.privatebank.business.service.workflow;

import com.privatebank.business.config.KycAsyncConfiguration;
import com.privatebank.business.service.kyc.KycWorkflowExecutionService;
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
}
