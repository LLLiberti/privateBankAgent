package com.privatebank.business.entity.workflow;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@TableName("agent_state")
public class AgentState {

    @TableId(value = "agent_state_id", type = IdType.INPUT)
    private String agentStateId;

    private String workflowId;

    private AgentType agentType;

    private AgentStatus agentStatus;

    private String executionId;

    private Integer retryCount;

    @Version
    private Long version;

    private String errorCode;

    private String errorMessage;

    private LocalDateTime startTime;

    private LocalDateTime finishTime;
}
