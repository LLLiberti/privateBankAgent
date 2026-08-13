package com.privatebank.agent.domain.kyc;

/** Raised when a KYC projection is not safe to cross the model boundary. */
public class KycInputValidationException extends RuntimeException {

    public KycInputValidationException(String message) {
        super(message);
    }
}
