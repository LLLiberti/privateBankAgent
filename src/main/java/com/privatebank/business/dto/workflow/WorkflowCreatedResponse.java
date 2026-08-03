package com.privatebank.business.dto.workflow;

import com.privatebank.business.enums.workflow.WorkflowStatus;

public record WorkflowCreatedResponse(String workflowId, WorkflowStatus workflowStatus) {
}
