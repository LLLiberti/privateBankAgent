package com.privatebank.workflow.repository;

import com.privatebank.workflow.domain.AgentState;
import com.privatebank.workflow.domain.AgentType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentStateRepository extends JpaRepository<AgentState, String> {

    List<AgentState> findByWorkflowIdOrderByAgentType(String workflowId);

    Optional<AgentState> findByWorkflowIdAndAgentType(String workflowId, AgentType agentType);
}
