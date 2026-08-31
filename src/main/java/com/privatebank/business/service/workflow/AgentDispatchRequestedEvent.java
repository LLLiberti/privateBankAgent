package com.privatebank.business.service.workflow;

import com.privatebank.business.dto.workflow.KycQaItem;
import com.privatebank.business.enums.workflow.AgentType;

import java.util.List;
import java.util.Map;

/**
 * Workflow-internal intent to claim an Agent execution and dispatch it.
 */
public record AgentDispatchRequestedEvent(
        String workflowId,
        AgentType agentType,
        Map<String, String> inputArtifactIds,
        String managerDescription,
        List<String> managerConfirmedItems,
        List<KycQaItem> qaItems) {

    public AgentDispatchRequestedEvent {
        inputArtifactIds = inputArtifactIds == null ? Map.of() : Map.copyOf(inputArtifactIds);
        managerConfirmedItems = managerConfirmedItems == null ? List.of() : List.copyOf(managerConfirmedItems);
        qaItems = qaItems == null ? List.of() : List.copyOf(qaItems);
    }

    public AgentDispatchRequestedEvent(
            String workflowId,
            AgentType agentType,
            Map<String, String> inputArtifactIds) {
        this(workflowId, agentType, inputArtifactIds, null, List.of(), List.of());
    }
}
