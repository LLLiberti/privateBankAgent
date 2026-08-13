package com.privatebank.agent.infrastructure.agentscope;

import com.privatebank.agent.application.runtime.AgentProgressEvent;
import com.privatebank.agent.application.runtime.AgentProgressPublisher;
import com.privatebank.business.service.workflow.WorkflowEventHub;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class WorkflowAgentProgressPublisher implements AgentProgressPublisher {

    private final WorkflowEventHub eventHub;

    @Override
    public void publish(AgentProgressEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("workflowId", event.workflowId());
        payload.put("executionId", event.executionId());
        payload.put("agentType", event.agentType());
        payload.put("stage", event.stage());
        payload.put("eventTime", event.eventTime().toString());
        payload.putAll(event.details());
        eventHub.publish(event.workflowId(), "AGENT_PROGRESS", payload);
    }
}
