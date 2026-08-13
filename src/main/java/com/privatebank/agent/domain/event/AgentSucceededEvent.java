package com.privatebank.agent.domain.event;

import com.privatebank.business.enums.workflow.AgentType;

/**
 * Published only after an Agent result and its artifact have been committed.
 * The event deliberately carries references rather than the result payload.
 */
public record AgentSucceededEvent(
        String workflowId,
        String agentStateId,
        AgentType agentType,
        String executionId,
        String artifactId) {
}
