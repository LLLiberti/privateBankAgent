package com.privatebank.workflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "agent_state", uniqueConstraints =
        @UniqueConstraint(name = "uk_agent_state_workflow_type", columnNames = {"workflow_id", "agent_type"}))
public class AgentState {

    @Id
    @Column(name = "agent_state_id", length = 64, nullable = false)
    private String agentStateId;

    @Column(name = "workflow_id", length = 64, nullable = false)
    private String workflowId;

    @Enumerated(EnumType.STRING)
    @Column(name = "agent_type", length = 32, nullable = false)
    private AgentType agentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "agent_status", length = 16, nullable = false)
    private AgentStatus agentStatus;

    @Column(name = "execution_id", length = 64)
    private String executionId;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "start_time")
    private LocalDateTime startTime;

    @Column(name = "finish_time")
    private LocalDateTime finishTime;
}
