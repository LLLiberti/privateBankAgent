package com.privatebank.agent.domain.kyc;

/** Raised when a KYC projection is not safe to cross the model boundary. */
public class KycInputValidationException extends RuntimeException {

    private final String reasonCode;
    private final String fieldPath;
    private final String rejectedValue;
    private final String matchedTerm;
    private final String category;

    public KycInputValidationException(String message) {
        this(message, "UNKNOWN", null, null, null, null);
    }

    public KycInputValidationException(
            String message,
            String reasonCode,
            String fieldPath,
            String rejectedValue,
            String matchedTerm,
            String category) {
        super(message);
        this.reasonCode = reasonCode;
        this.fieldPath = fieldPath;
        this.rejectedValue = rejectedValue;
        this.matchedTerm = matchedTerm;
        this.category = category;
    }

    public String reasonCode() {
        return reasonCode;
    }

    public String fieldPath() {
        return fieldPath;
    }

    public String rejectedValue() {
        return rejectedValue;
    }

    public String matchedTerm() {
        return matchedTerm;
    }

    public String category() {
        return category;
    }
}
