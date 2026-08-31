package com.privatebank.business.service.workflow;

import com.privatebank.business.enums.workflow.AgentType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class WorkflowAgentDispatchListener {

    private final WorkflowAgentDispatchService dispatchService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onWorkflowCreated(WorkflowCreatedEvent event) {
        dispatchService.dispatch(new AgentDispatchRequestedEvent(
                event.workflowId(), AgentType.CUSTOMER_INSIGHT, Map.of()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onKycRegenerationRequested(KycRegenerationRequestedEvent event) {
        dispatchService.dispatch(new AgentDispatchRequestedEvent(
                event.workflowId(),
                AgentType.CUSTOMER_INSIGHT,
                Map.of(),
                event.managerDescription(),
                event.managerConfirmedItems(),
                event.qaItems()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDownstreamAgentsReady(DownstreamAgentsReadyEvent event) {
        for (AgentType agentType : event.agentTypes()) {
            if (agentType == AgentType.MARKET_INSIGHT || agentType == AgentType.PRODUCT_EXPERT) {
                dispatchService.dispatch(new AgentDispatchRequestedEvent(
                        event.workflowId(),
                        agentType,
                        Map.of("kycArtifactId", event.kycArtifactId())));
            }
        }
    }

    @EventListener
    public void onAgentDispatchRequested(AgentDispatchRequestedEvent event) {
        dispatchService.dispatch(event);
    }
}
