package com.privatebank.business.dto.workflow;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record CreateWorkflowRequest(
        @NotNull Long customerId,
        @NotNull @Positive Long importBatchId,
        @NotNull LocalDate asOfDate,
        @NotBlank @Size(max = 64) String templateId,
        @Size(max = 2000) String analysisRequirements) {
}
