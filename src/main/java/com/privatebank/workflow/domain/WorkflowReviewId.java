package com.privatebank.workflow.domain;

import java.io.Serializable;

public record WorkflowReviewId(String workflowId, Integer reviewRound) implements Serializable {
}
