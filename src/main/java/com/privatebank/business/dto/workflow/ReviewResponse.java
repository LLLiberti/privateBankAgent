package com.privatebank.business.dto.workflow;

import com.privatebank.business.entity.workflow.ReviewStatus;
import com.privatebank.business.entity.workflow.WorkflowStatus;

public record ReviewResponse(ReviewStatus reviewStatus, WorkflowStatus workflowStatus, int reviewRound) {
}
