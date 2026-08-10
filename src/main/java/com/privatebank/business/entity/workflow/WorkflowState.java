package com.privatebank.business.entity.workflow;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.privatebank.business.enums.workflow.WorkflowStatus;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@TableName("workflow_state")
public class WorkflowState {

    @TableId(value = "workflow_id", type = IdType.INPUT)
    private String workflowId;

    private Long personId;

    private Long importBatchId;

    private String createdBy;

    private LocalDate asOfDate;

    private String templateId;

    private String analysisRequirements;

    private WorkflowStatus workflowStatus;

    @Version
    private Long version;

    private String errorCode;

    private String errorMessage;

    private LocalDateTime startTime;

    private LocalDateTime finishTime;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
