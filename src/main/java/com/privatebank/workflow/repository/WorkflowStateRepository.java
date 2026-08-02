package com.privatebank.workflow.repository;

import com.privatebank.workflow.domain.WorkflowState;
import com.privatebank.workflow.domain.WorkflowStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;

public interface WorkflowStateRepository extends JpaRepository<WorkflowState, String> {

    long countByWorkflowStatusIn(Collection<WorkflowStatus> statuses);

    Page<WorkflowState> findByWorkflowStatus(WorkflowStatus status, Pageable pageable);
}
