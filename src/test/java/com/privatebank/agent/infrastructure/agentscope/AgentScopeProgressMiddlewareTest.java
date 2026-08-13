package com.privatebank.agent.infrastructure.agentscope;

import com.privatebank.agent.application.runtime.AgentExecutionRequest;
import com.privatebank.agent.application.runtime.AgentProgressEvent;
import com.privatebank.business.enums.workflow.AgentType;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.model.ChatUsage;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentScopeProgressMiddlewareTest {

    @Test
    void publishesSafeProgressWithoutPromptOrToolArguments() {
        List<AgentProgressEvent> published = new ArrayList<>();
        AgentExecutionRequest<String> request = new AgentExecutionRequest<>(
                "WF-1", "EXE-1", AgentType.CUSTOMER_INSIGHT, "SYSTEM", "sensitive-input", Map.of());
        AgentScopeProgressMiddleware middleware = new AgentScopeProgressMiddleware(request, published::add);
        Msg result = Msg.builderForRole(MsgRole.ASSISTANT)
                .textContent("sensitive-output")
                .generateReason(GenerateReason.STRUCTURED_OUTPUT)
                .build();
        List<AgentEvent> events = List.of(
                new ModelCallEndEvent("reply", new ChatUsage(10, 5, 0)),
                new ToolCallStartEvent("reply", "tool-1", "read_customer_facts"),
                new AgentResultEvent(result));

        middleware.onAgent(
                        mock(Agent.class), RuntimeContext.empty(), new AgentInput(List.of()), ignored -> Flux.fromIterable(events))
                .blockLast();

        assertThat(published).hasSize(3);
        assertThat(published.get(0).details()).containsEntry("inputTokens", 10).containsEntry("outputTokens", 5);
        assertThat(published.get(1).details()).containsEntry("toolName", "read_customer_facts");
        assertThat(published.get(2).details()).containsEntry("generateReason", "STRUCTURED_OUTPUT");
        assertThat(published.toString()).doesNotContain("sensitive-input", "sensitive-output");
    }
}
