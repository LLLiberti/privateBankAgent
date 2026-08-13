package com.privatebank.business.dto.workflow;

import com.privatebank.business.entity.workflow.WorkflowState;
import com.privatebank.business.enums.workflow.WorkflowStatus;
import com.privatebank.business.enums.workflow.AgentStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record WorkflowDetailResponse(
        String workflowId,
        Long customerId,
        Long importBatchId,
        WorkflowStatus workflowStatus,
        String currentStage,
        Long version,
        LocalDate asOfDate,
        String templateId,
        List<AgentStateResponse> agentStates,
        String errorCode,
        String errorMessage,
        LocalDateTime startTime,
        LocalDateTime finishTime) {

    public static WorkflowDetailResponse from(WorkflowState workflow, List<AgentStateResponse> states) {
        String currentStage = states.stream()
                .filter(state -> state.agentStatus() == AgentStatus.RUNNING
                        || state.agentStatus() == AgentStatus.READY)
                .map(state -> state.agentType().name())
                .findFirst()
                .orElse(workflow.getWorkflowStatus().name());
        return new WorkflowDetailResponse(
                workflow.getWorkflowId(), workflow.getPersonId(), workflow.getImportBatchId(),
                workflow.getWorkflowStatus(), currentStage,
                workflow.getVersion(), workflow.getAsOfDate(), workflow.getTemplateId(), states,
                workflow.getErrorCode(), workflow.getErrorMessage(), workflow.getStartTime(), workflow.getFinishTime());
    }
}
