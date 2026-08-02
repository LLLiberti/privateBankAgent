package com.privatebank.workflow.api;

import com.privatebank.workflow.domain.ReviewStatus;
import com.privatebank.workflow.domain.WorkflowStatus;

public record ReviewResponse(ReviewStatus reviewStatus, WorkflowStatus workflowStatus, int reviewRound) {
}
