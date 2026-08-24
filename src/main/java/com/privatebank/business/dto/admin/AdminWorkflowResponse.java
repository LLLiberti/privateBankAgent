package com.privatebank.business.dto.admin;

import com.privatebank.business.entity.workflow.WorkflowState;
import com.privatebank.business.enums.workflow.WorkflowStatus;

import java.time.LocalDateTime;

public record AdminWorkflowResponse(
        String workflowId,
        Long customerId,
        WorkflowStatus status,
        String errorCode,
        String errorMessage,
        Long version,
        LocalDateTime updatedAt) {

    public static AdminWorkflowResponse from(WorkflowState workflow) {
        return new AdminWorkflowResponse(
                workflow.getWorkflowId(), workflow.getPersonId(), workflow.getWorkflowStatus(),
                workflow.getErrorCode(), workflow.getErrorMessage(), workflow.getVersion(), workflow.getUpdatedAt());
    }
}
