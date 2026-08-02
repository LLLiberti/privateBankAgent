package com.privatebank.business.entity.workflow;

public enum WorkflowStatus {
    CREATED,
    RUNNING,
    WAITING_INPUT,
    WAITING_REVIEW,
    GENERATING_OUTPUT,
    COMPLETED,
    FAILED,
    CANCELED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELED;
    }
}
