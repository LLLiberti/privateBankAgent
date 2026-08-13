package com.privatebank.business.dto.workflow;

import com.privatebank.business.entity.workflow.AgentState;
import com.privatebank.business.enums.workflow.AgentStatus;
import com.privatebank.business.enums.workflow.AgentType;

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
