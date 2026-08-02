package com.privatebank.business.dto.workflow;

import com.privatebank.business.entity.workflow.WorkflowStatus;

public record WorkflowCreatedResponse(String workflowId, WorkflowStatus workflowStatus) {
}
