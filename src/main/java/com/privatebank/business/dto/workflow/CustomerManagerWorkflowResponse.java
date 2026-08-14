package com.privatebank.business.dto.workflow;

import com.privatebank.business.enums.workflow.WorkflowStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record CustomerManagerWorkflowResponse(
        String workflowId,
        Long customerId,
        String customerName,
        WorkflowStatus workflowStatus,
        String templateId,
        LocalDate asOfDate,
        LocalDateTime updatedAt) {
}
