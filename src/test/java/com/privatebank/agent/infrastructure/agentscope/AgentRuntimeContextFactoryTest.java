package com.privatebank.agent.infrastructure.agentscope;

import com.privatebank.agent.application.runtime.AgentExecutionRequest;
import com.privatebank.business.enums.workflow.AgentType;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRuntimeContextFactoryTest {

    @Test
    void isolatesEveryAgentExecutionByTypeWorkflowAndExecutionId() {
        AgentExecutionRequest<String> request = new AgentExecutionRequest<>(
                "WF-1", "EXE-2", AgentType.PRODUCT_EXPERT, "USER-1", "input", Map.of("asOf", "2026-08-13"));

        RuntimeContext context = new AgentRuntimeContextFactory().create(request);

        assertThat(context.getUserId()).isEqualTo("USER-1");
        assertThat(context.getSessionId()).isEqualTo("PRODUCT_EXPERT:WF-1:EXE-2");
        assertThat(context.<String>get("workflowId")).isEqualTo("WF-1");
        assertThat(context.<String>get("executionId")).isEqualTo("EXE-2");
        assertThat(context.<String>get("agentType")).isEqualTo("PRODUCT_EXPERT");
        assertThat(context.<String>get("asOf")).isEqualTo("2026-08-13");
    }
}
