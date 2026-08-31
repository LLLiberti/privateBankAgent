package com.privatebank.business.service.workflow;

import com.privatebank.business.enums.workflow.AgentType;

public record WorkflowAgentExecutionClaim(
        String workflowId,
        String agentStateId,
        AgentType agentType,
        String executionId,
        String operatorUserId,
        Long personId) {
}
