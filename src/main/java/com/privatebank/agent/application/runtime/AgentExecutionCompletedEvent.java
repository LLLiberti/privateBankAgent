package com.privatebank.agent.application.runtime;

import com.privatebank.business.enums.workflow.AgentType;

public record AgentExecutionCompletedEvent(
        String workflowId,
        String agentStateId,
        AgentType agentType,
        String executionId,
        String resultJson,
        String complianceResult,
        int retryCountIncrement) {

    public AgentExecutionCompletedEvent {
        retryCountIncrement = Math.max(0, retryCountIncrement);
    }
}
