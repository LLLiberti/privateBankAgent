package com.privatebank.agent.adapter.workflow;

import com.privatebank.agent.application.downstream.DownstreamAgentExecutionService;
import com.privatebank.agent.domain.event.AgentExecutionRequestedEvent;
import com.privatebank.business.enums.workflow.AgentType;
import com.privatebank.business.service.workflow.DownstreamAgentsReadyEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class DownstreamAgentWorkflowListenerTest {

    @Test
    void dispatchesBothParallelAgentsWhenKycIsApproved() {
        DownstreamAgentExecutionService executionService = mock(DownstreamAgentExecutionService.class);
        DownstreamAgentWorkflowListener listener = new DownstreamAgentWorkflowListener(executionService);

        listener.onDownstreamAgentsReady(new DownstreamAgentsReadyEvent(
                "WF-1", "ART-KYC-2", List.of(AgentType.MARKET_INSIGHT, AgentType.PRODUCT_EXPERT)));

        verify(executionService).executeMarketInsight("WF-1", "ART-KYC-2");
        verify(executionService).executeProductExpert("WF-1", "ART-KYC-2");
    }

    @Test
    void dispatchesCommittedExecutionRequestsToTheMatchingAgent() {
        DownstreamAgentExecutionService executionService = mock(DownstreamAgentExecutionService.class);
        DownstreamAgentWorkflowListener listener = new DownstreamAgentWorkflowListener(executionService);

        listener.onAgentExecutionRequested(new AgentExecutionRequestedEvent(
                "WF-1", AgentType.MARKET_INSIGHT, Map.of("kycArtifactId", "ART-KYC")));
        listener.onAgentExecutionRequested(new AgentExecutionRequestedEvent(
                "WF-1", AgentType.PRODUCT_EXPERT, Map.of("kycArtifactId", "ART-KYC")));
        listener.onAgentExecutionRequested(new AgentExecutionRequestedEvent(
                "WF-1", AgentType.SOLUTION_DESIGN, Map.of(
                        "kycArtifactId", "ART-KYC",
                        "marketArtifactId", "ART-MARKET",
                        "kypArtifactId", "ART-KYP")));
        listener.onAgentExecutionRequested(new AgentExecutionRequestedEvent(
                "WF-1", AgentType.COMPLIANCE_CHECK, Map.of("cfsArtifactId", "ART-CFS")));

        verify(executionService).executeMarketInsight("WF-1", "ART-KYC");
        verify(executionService).executeProductExpert("WF-1", "ART-KYC");
        verify(executionService).executeCfsDesign("WF-1", "ART-KYC", "ART-MARKET", "ART-KYP");
        verify(executionService).executeComplianceCheck("WF-1", "ART-CFS");
    }

    @Test
    void ignoresUnexpectedKycExecutionRequest() {
        DownstreamAgentExecutionService executionService = mock(DownstreamAgentExecutionService.class);
        DownstreamAgentWorkflowListener listener = new DownstreamAgentWorkflowListener(executionService);

        listener.onAgentExecutionRequested(new AgentExecutionRequestedEvent(
                "WF-1", AgentType.CUSTOMER_INSIGHT, Map.of()));

        verifyNoInteractions(executionService);
    }
}
