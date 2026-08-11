package com.privatebank.business.service.kyc;

public class KycWorkflowStateConflictException extends RuntimeException {

    public KycWorkflowStateConflictException(String message) {
        super(message);
    }
}
