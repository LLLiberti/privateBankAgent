package com.privatebank.agent.application.kyc;

public class KycWorkflowStateConflictException extends RuntimeException {

    public KycWorkflowStateConflictException(String message) {
        super(message);
    }
}
