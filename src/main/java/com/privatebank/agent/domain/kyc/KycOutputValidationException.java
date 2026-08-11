package com.privatebank.agent.domain.kyc;

public class KycOutputValidationException extends RuntimeException {

    public KycOutputValidationException(String message) {
        super(message);
    }
}
