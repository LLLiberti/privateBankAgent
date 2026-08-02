package com.privatebank.workflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "workflow_state")
public class WorkflowState {

    @Id
    @Column(name = "workflow_id", length = 64, nullable = false)
    private String workflowId;

    @Column(name = "person_id", nullable = false)
    private Long personId;

    @Column(name = "created_by", length = 64, nullable = false)
    private String createdBy;

    @Column(name = "as_of_date", nullable = false)
    private LocalDate asOfDate;

    @Column(name = "template_id", length = 64, nullable = false)
    private String templateId;

    @Column(name = "analysis_requirements", columnDefinition = "text")
    private String analysisRequirements;

    @Enumerated(EnumType.STRING)
    @Column(name = "workflow_status", length = 32, nullable = false)
    private WorkflowStatus workflowStatus;

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

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
