package com.privatebank.agent.application.kyc;

public record KycExecutionClaim(String workflowId, Long personId, String executionId, String operatorUserId) {
}
