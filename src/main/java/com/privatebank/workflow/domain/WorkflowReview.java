package com.privatebank.workflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@Entity
@IdClass(WorkflowReviewId.class)
@Table(name = "workflow_review")
public class WorkflowReview {

    @Id
    @Column(name = "workflow_id", length = 64, nullable = false)
    private String workflowId;

    @Column(name = "reviewer_id", length = 64, nullable = false)
    private String reviewerId;

    @Column(name = "cfs_artifact_id", length = 64, nullable = false)
    private String cfsArtifactId;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", length = 16, nullable = false)
    private ReviewStatus reviewStatus;

    @Column(name = "review_comments", columnDefinition = "text")
    private String reviewComments;

    @Id
    @Column(name = "review_round", nullable = false)
    private Integer reviewRound;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "review_time", nullable = false)
    private LocalDateTime reviewTime;
}
