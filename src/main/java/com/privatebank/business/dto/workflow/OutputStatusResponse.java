package com.privatebank.business.dto.workflow;

import com.privatebank.business.enums.workflow.WorkflowStatus;

import java.util.List;

public record OutputStatusResponse(String status, WorkflowStatus workflowStatus, List<String> formats) {
}
