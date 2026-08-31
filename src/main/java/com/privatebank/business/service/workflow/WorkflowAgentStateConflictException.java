package com.privatebank.business.service.workflow;

public class WorkflowAgentStateConflictException extends RuntimeException {

    public WorkflowAgentStateConflictException(String message) {
        super(message);
    }
}
