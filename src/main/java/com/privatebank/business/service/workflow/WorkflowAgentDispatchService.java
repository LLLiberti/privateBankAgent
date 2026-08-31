package com.privatebank.business.service.workflow;

import com.privatebank.agent.application.runtime.AgentExecutionFailedEvent;
import com.privatebank.agent.application.runtime.AgentExecutionRequestedEvent;
import com.privatebank.business.entity.workflow.AgentArtifact;
import com.privatebank.business.mapper.workflow.AgentArtifactMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class WorkflowAgentDispatchService {

    private final WorkflowAgentStateService stateService;
    private final AgentArtifactMapper artifactMapper;
    private final ApplicationEventPublisher eventPublisher;

    public void dispatch(AgentDispatchRequestedEvent request) {
        stateService.claim(request.workflowId(), request.agentType())
                .ifPresent(claim -> dispatchClaimed(request, claim));
    }

    private void dispatchClaimed(
            AgentDispatchRequestedEvent request,
            WorkflowAgentExecutionClaim claim) {
        try {
            Map<String, String> artifactResults = loadArtifactResults(
                    request.workflowId(), request.inputArtifactIds());
            eventPublisher.publishEvent(new AgentExecutionRequestedEvent(
                    claim.workflowId(),
                    claim.agentStateId(),
                    claim.agentType(),
                    claim.executionId(),
                    claim.operatorUserId(),
                    claim.personId(),
                    request.inputArtifactIds(),
                    artifactResults,
                    request.managerDescription(),
                    request.managerConfirmedItems(),
                    request.qaItems()));
        } catch (RuntimeException exception) {
            eventPublisher.publishEvent(new AgentExecutionFailedEvent(
                    claim.workflowId(),
                    claim.agentStateId(),
                    claim.agentType(),
                    claim.executionId(),
                    "AGENT_INPUT_ARTIFACT_INVALID",
                    exception.getMessage()));
        }
    }

    private Map<String, String> loadArtifactResults(
            String workflowId,
            Map<String, String> artifactIds) {
        Map<String, String> results = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : artifactIds.entrySet()) {
            AgentArtifact artifact = artifactMapper.selectById(entry.getValue());
            if (artifact == null || !workflowId.equals(artifact.getWorkflowId())) {
                throw new IllegalStateException("Artifact不存在或不属于当前工作流: " + entry.getKey());
            }
            results.put(entry.getKey(), artifact.getResult());
        }
        return Map.copyOf(results);
    }
}
