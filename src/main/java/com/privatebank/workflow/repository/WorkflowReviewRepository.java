package com.privatebank.workflow.repository;

import com.privatebank.workflow.domain.WorkflowReview;
import com.privatebank.workflow.domain.WorkflowReviewId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WorkflowReviewRepository extends JpaRepository<WorkflowReview, WorkflowReviewId> {

    Optional<WorkflowReview> findFirstByWorkflowIdOrderByReviewRoundDesc(String workflowId);
}
