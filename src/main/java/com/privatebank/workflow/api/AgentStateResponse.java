package com.privatebank.workflow.api;

import com.privatebank.workflow.domain.AgentState;
import com.privatebank.workflow.domain.AgentStatus;
import com.privatebank.workflow.domain.AgentType;

public record AgentStateResponse(
        String agentStateId,
        AgentType agentType,
        AgentStatus agentStatus,
        String executionId,
        Integer retryCount,
        String errorCode,
        String errorMessage) {

    public static AgentStateResponse from(AgentState state) {
        return new AgentStateResponse(
                state.getAgentStateId(), state.getAgentType(), state.getAgentStatus(), state.getExecutionId(),
                state.getRetryCount(), state.getErrorCode(), state.getErrorMessage());
    }
}
