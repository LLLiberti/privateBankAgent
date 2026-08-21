package com.privatebank.agent.domain.event;

import com.privatebank.business.enums.workflow.AgentType;

import java.util.Map;

/**
 * Internal event used to ask the adapter layer to start a non-KYC Agent.
 * Only artifact references are passed; full results are loaded from agent_artifact.
 */
public record AgentExecutionRequestedEvent(
        String workflowId,
        AgentType agentType,
        Map<String, String> inputArtifactIds) {

    public AgentExecutionRequestedEvent {
        inputArtifactIds = inputArtifactIds == null ? Map.of() : Map.copyOf(inputArtifactIds);
    }
}
