package com.privatebank.workflow.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@TableName("agent_artifact")
public class AgentArtifact {

    @TableId(value = "artifact_id", type = IdType.INPUT)
    private String artifactId;

    private String workflowId;

    private String agentStateId;

    private AgentType agentType;

    private String executionId;

    private String complianceResult;

    private String result;

    private String storageKey;

    private Integer version;

    private LocalDateTime createTime;
}
