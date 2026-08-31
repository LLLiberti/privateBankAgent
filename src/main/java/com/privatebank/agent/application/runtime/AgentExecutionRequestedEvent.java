package com.privatebank.agent.application.runtime;

import com.privatebank.business.dto.workflow.KycQaItem;
import com.privatebank.business.enums.workflow.AgentType;

import java.util.List;
import java.util.Map;

/**
 * Claimed execution command consumed by the Agent layer.
 * Workflow state has already moved to RUNNING when this event is published.
 */
public record AgentExecutionRequestedEvent(
        String workflowId,
        String agentStateId,
        AgentType agentType,
        String executionId,
        String operatorUserId,
        Long personId,
        Map<String, String> inputArtifactIds,
        Map<String, String> inputArtifactResults,
        String managerDescription,
        List<String> managerConfirmedItems,
        List<KycQaItem> qaItems) {

    public AgentExecutionRequestedEvent {
        inputArtifactIds = inputArtifactIds == null ? Map.of() : Map.copyOf(inputArtifactIds);
        inputArtifactResults = inputArtifactResults == null ? Map.of() : Map.copyOf(inputArtifactResults);
        managerConfirmedItems = managerConfirmedItems == null ? List.of() : List.copyOf(managerConfirmedItems);
        qaItems = qaItems == null ? List.of() : List.copyOf(qaItems);
    }
}
