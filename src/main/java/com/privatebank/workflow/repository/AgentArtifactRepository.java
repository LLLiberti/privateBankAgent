package com.privatebank.workflow.repository;

import com.privatebank.workflow.domain.AgentArtifact;
import com.privatebank.workflow.domain.AgentType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AgentArtifactRepository extends JpaRepository<AgentArtifact, String> {

    Page<AgentArtifact> findByWorkflowId(String workflowId, Pageable pageable);

    Page<AgentArtifact> findByWorkflowIdAndAgentType(String workflowId, AgentType agentType, Pageable pageable);

    Optional<AgentArtifact> findFirstByWorkflowIdAndAgentTypeOrderByVersionDesc(
            String workflowId, AgentType agentType);
}
