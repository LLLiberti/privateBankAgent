package com.privatebank.agent.application.runtime;

import com.privatebank.business.enums.workflow.AgentType;

public record AgentExecutionClaim(
        String workflowId,
        String agentStateId,
        AgentType agentType,
        String executionId,
        String operatorUserId) {
}
