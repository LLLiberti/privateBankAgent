package com.privatebank.admin.api;

import com.privatebank.workflow.domain.WorkflowState;
import com.privatebank.workflow.domain.WorkflowStatus;

import java.time.LocalDateTime;

public record AdminWorkflowResponse(
        String workflowId,
        Long customerId,
        WorkflowStatus status,
        String errorCode,
        String errorMessage,
        LocalDateTime updatedAt) {

    public static AdminWorkflowResponse from(WorkflowState workflow) {
        return new AdminWorkflowResponse(
                workflow.getWorkflowId(), workflow.getPersonId(), workflow.getWorkflowStatus(),
                workflow.getErrorCode(), workflow.getErrorMessage(), workflow.getUpdatedAt());
    }
}
