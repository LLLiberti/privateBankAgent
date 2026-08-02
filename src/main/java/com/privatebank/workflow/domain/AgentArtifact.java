package com.privatebank.workflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "agent_artifact", uniqueConstraints = @UniqueConstraint(
        name = "uk_agent_artifact_execution", columnNames = {"agent_state_id", "execution_id", "agent_type"}))
public class AgentArtifact {

    @Id
    @Column(name = "artifact_id", length = 64, nullable = false)
    private String artifactId;

    @Column(name = "workflow_id", length = 64, nullable = false)
    private String workflowId;

    @Column(name = "agent_state_id", length = 64, nullable = false)
    private String agentStateId;

    @Enumerated(EnumType.STRING)
    @Column(name = "agent_type", length = 32, nullable = false)
    private AgentType agentType;

    @Column(name = "execution_id", length = 64, nullable = false)
    private String executionId;

    @Column(name = "compliance_result", length = 24)
    private String complianceResult;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result", columnDefinition = "json")
    private String result;

    @Column(name = "storage_key", length = 1000)
    private String storageKey;

    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;
}
