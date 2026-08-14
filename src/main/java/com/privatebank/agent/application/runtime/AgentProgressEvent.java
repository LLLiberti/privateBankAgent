package com.privatebank.agent.application.runtime;

import com.privatebank.business.enums.workflow.AgentType;

import java.time.Instant;
import java.util.Map;

public record AgentProgressEvent(
        String workflowId,
        String executionId,
        AgentType agentType,
        String stage,
        Instant eventTime,
        Map<String, Object> details) {

    public AgentProgressEvent {
        details = details == null ? Map.of() : Map.copyOf(details);
    }
}
