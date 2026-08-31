package com.privatebank.business.service.workflow;

import com.privatebank.agent.application.runtime.AgentProgressEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Translates Agent progress into the workflow's client-facing event stream. */
@Component
@RequiredArgsConstructor
public class WorkflowAgentProgressListener {

    private final WorkflowEventHub eventHub;

    @EventListener
    public void onAgentProgress(AgentProgressEvent event) {
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
