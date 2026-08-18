package com.privatebank.business.dto.workflow;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Identifies the failed KYC execution that the manager intends to retry.
 */
public record CustomerInsightRetryRequest(
        @NotBlank @Size(max = 64) String failedExecutionId,
        @Size(max = 600) String description,
        @Size(max = 20) List<@Size(max = 128) String> confirmedItems) {

    public CustomerInsightRetryRequest(String failedExecutionId) {
        this(failedExecutionId, null, List.of());
    }
}
