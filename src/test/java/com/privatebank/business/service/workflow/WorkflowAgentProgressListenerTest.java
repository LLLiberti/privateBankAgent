package com.privatebank.business.service.workflow;

import com.privatebank.agent.application.runtime.AgentProgressEvent;
import com.privatebank.business.enums.workflow.AgentType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WorkflowAgentProgressListenerTest {

    @Test
    void translatesAgentProgressIntoWorkflowEventStream() {
        WorkflowEventHub eventHub = mock(WorkflowEventHub.class);
        WorkflowAgentProgressListener listener = new WorkflowAgentProgressListener(eventHub);
        AgentProgressEvent event = new AgentProgressEvent(
                "WF-1", "EXE-1", AgentType.CUSTOMER_INSIGHT,
                "MODEL_CALL_STARTED", Instant.parse("2026-08-31T12:00:00Z"),
                Map.of("attempt", 1));

        listener.onAgentProgress(event);

        verify(eventHub).publish(eq("WF-1"), eq("AGENT_PROGRESS"), argThat(payload -> {
            if (!(payload instanceof Map<?, ?> values)) {
                return false;
            }
            return values.get("executionId").equals("EXE-1")
                    && values.get("stage").equals("MODEL_CALL_STARTED")
                    && values.get("attempt").equals(1);
        }));
    }
}
