package com.privatebank.agent.domain.event;

import com.privatebank.business.enums.workflow.AgentType;

/** Published only after the Agent failure state has been committed. */
public record AgentFailedEvent(
        String workflowId,
        String agentStateId,
        AgentType agentType,
        String executionId,
        String errorCode) {
}
