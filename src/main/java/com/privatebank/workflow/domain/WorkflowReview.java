package com.privatebank.workflow.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@TableName("workflow_review")
public class WorkflowReview {

    private String workflowId;

    private String reviewerId;

    private String cfsArtifactId;

    private ReviewStatus reviewStatus;

    private String reviewComments;

    private Integer reviewRound;

    @Version
    private Long version;

    private LocalDateTime reviewTime;
}
