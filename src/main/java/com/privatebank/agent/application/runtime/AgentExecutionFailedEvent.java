package com.privatebank.agent.application.runtime;

import com.privatebank.business.enums.workflow.AgentType;

public record AgentExecutionFailedEvent(
        String workflowId,
        String agentStateId,
        AgentType agentType,
        String executionId,
        String errorCode,
        String errorMessage) {
}
