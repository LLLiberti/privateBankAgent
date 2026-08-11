package com.privatebank.business.service.kyc;

public record KycExecutionClaim(String workflowId, Long personId, String executionId) {
}
