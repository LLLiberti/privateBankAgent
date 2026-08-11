package com.privatebank.agent.domain.kyc;

public class KycModelInvocationException extends RuntimeException {

    public KycModelInvocationException(String message) {
        super(message);
    }

    public KycModelInvocationException(String message, Throwable cause) {
        super(message, cause);
    }
}
