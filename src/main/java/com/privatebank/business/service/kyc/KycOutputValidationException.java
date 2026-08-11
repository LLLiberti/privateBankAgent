package com.privatebank.business.service.kyc;

public class KycOutputValidationException extends RuntimeException {

    public KycOutputValidationException(String message) {
        super(message);
    }
}
