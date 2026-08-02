package com.privatebank.workflow.api;

import com.privatebank.workflow.domain.WorkflowStatus;

import java.util.List;

public record OutputStatusResponse(String status, WorkflowStatus workflowStatus, List<String> formats) {
}
