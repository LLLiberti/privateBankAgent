package com.privatebank.workflow.api;

import com.privatebank.workflow.domain.WorkflowStatus;

public record WorkflowCreatedResponse(String workflowId, WorkflowStatus workflowStatus) {
}
