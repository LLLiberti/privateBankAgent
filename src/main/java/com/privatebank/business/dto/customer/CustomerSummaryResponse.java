package com.privatebank.business.dto.customer;

public record CustomerSummaryResponse(
        Long customerId,
        String fullName,
        String displayName,
        String personType,
        String verificationStatus,
        String riskLevel) {
}
