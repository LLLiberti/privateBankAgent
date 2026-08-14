package com.privatebank.business.dto.workflow;

import com.privatebank.business.enums.workflow.ReviewStatus;
import com.privatebank.business.enums.workflow.WorkflowStatus;

public record ReviewResponse(ReviewStatus reviewStatus, WorkflowStatus workflowStatus, int reviewRound) {
}
