package com.privatebank.agent.infrastructure.workflow;

public class AgentWorkflowStateConflictException extends RuntimeException {

    public AgentWorkflowStateConflictException(String message) {
        super(message);
    }
}
