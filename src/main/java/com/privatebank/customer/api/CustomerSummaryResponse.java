package com.privatebank.customer.api;

public record CustomerSummaryResponse(
        Long customerId,
        String fullName,
        String displayName,
        String personType,
        String verificationStatus,
        String riskLevel) {
}
