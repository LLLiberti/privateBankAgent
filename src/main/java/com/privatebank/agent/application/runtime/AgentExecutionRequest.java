package com.privatebank.agent.application.runtime;

import com.privatebank.business.enums.workflow.AgentType;

import java.util.Map;

/** Runtime-only request. Business persistence remains owned by the workflow layer. */
public record AgentExecutionRequest<I>(
        String workflowId,
        String executionId,
        AgentType agentType,
        String operatorUserId,
        I input,
        Map<String, Object> attributes) {

    public AgentExecutionRequest {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
