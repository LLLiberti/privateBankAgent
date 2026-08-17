package com.privatebank.business.dto.workflow;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Identifies the failed KYC execution that the manager intends to retry.
 */
public record CustomerInsightRetryRequest(
        @NotBlank @Size(max = 64) String failedExecutionId) {
}
