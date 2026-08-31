package com.privatebank.agent.adapter.workflow;

import com.privatebank.agent.application.downstream.DownstreamAgentExecutionService;
import com.privatebank.agent.application.kyc.KycWorkflowExecutionService;
import com.privatebank.agent.application.runtime.AgentExecutionRequestedEvent;
import com.privatebank.agent.infrastructure.kyc.KycAsyncConfiguration;
import com.privatebank.business.enums.workflow.AgentType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AgentExecutionListener {

    private final KycWorkflowExecutionService kycExecutionService;
    private final DownstreamAgentExecutionService downstreamExecutionService;

    @Async(KycAsyncConfiguration.KYC_EXECUTOR)
    @EventListener
    public void onAgentExecutionRequested(AgentExecutionRequestedEvent event) {
        if (event.agentType() == AgentType.CUSTOMER_INSIGHT) {
            kycExecutionService.execute(event);
            return;
        }
        downstreamExecutionService.execute(event);
    }
}
