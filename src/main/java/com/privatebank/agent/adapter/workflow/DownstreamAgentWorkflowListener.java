package com.privatebank.agent.adapter.workflow;

import com.privatebank.agent.application.downstream.DownstreamAgentExecutionService;
import com.privatebank.agent.domain.event.AgentExecutionRequestedEvent;
import com.privatebank.agent.infrastructure.kyc.KycAsyncConfiguration;
import com.privatebank.business.enums.workflow.AgentType;
import com.privatebank.business.service.workflow.DownstreamAgentsReadyEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class DownstreamAgentWorkflowListener {

    private final DownstreamAgentExecutionService executionService;

    @Async(KycAsyncConfiguration.KYC_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDownstreamAgentsReady(DownstreamAgentsReadyEvent event) {
        for (AgentType agentType : event.agentTypes()) {
            if (agentType == AgentType.MARKET_INSIGHT) {
                executionService.executeMarketInsight(event.workflowId(), event.kycArtifactId());
            } else if (agentType == AgentType.PRODUCT_EXPERT) {
                executionService.executeProductExpert(event.workflowId(), event.kycArtifactId());
            }
        }
    }

    @Async(KycAsyncConfiguration.KYC_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAgentExecutionRequested(AgentExecutionRequestedEvent event) {
        switch (event.agentType()) {
            case MARKET_INSIGHT -> executionService.executeMarketInsight(
                    event.workflowId(), event.inputArtifactIds().get("kycArtifactId"));
            case PRODUCT_EXPERT -> executionService.executeProductExpert(
                    event.workflowId(), event.inputArtifactIds().get("kycArtifactId"));
            case SOLUTION_DESIGN -> executionService.executeCfsDesign(
                    event.workflowId(),
                    event.inputArtifactIds().get("kycArtifactId"),
                    event.inputArtifactIds().get("marketArtifactId"),
                    event.inputArtifactIds().get("kypArtifactId"));
            case COMPLIANCE_CHECK -> executionService.executeComplianceCheck(
                    event.workflowId(), event.inputArtifactIds().get("cfsArtifactId"));
            default -> {
                // KYC is handled by KycWorkflowListener; other types are not expected here.
            }
        }
    }
}
