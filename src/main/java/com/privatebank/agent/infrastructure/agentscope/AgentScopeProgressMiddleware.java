package com.privatebank.agent.infrastructure.agentscope;

import com.privatebank.agent.application.runtime.AgentExecutionRequest;
import com.privatebank.agent.application.runtime.AgentProgressEvent;
import com.privatebank.agent.application.runtime.AgentProgressPublisher;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import reactor.core.publisher.Flux;

@RequiredArgsConstructor
public class AgentScopeProgressMiddleware implements MiddlewareBase {

    private final AgentExecutionRequest<?> request;
    private final AgentProgressPublisher publisher;

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext context,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        return next.apply(input)
                .doOnNext(this::publish)
                .doOnError(error -> publish("RUNTIME_FAILED", Map.of(
                        "failureType", error.getClass().getSimpleName())))
                .doOnCancel(() -> publish("RUNTIME_INTERRUPTED", Map.of()));
    }

    private void publish(AgentEvent event) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("runtimeEvent", event.getType().getValue());
        if (event instanceof ModelCallEndEvent modelEnd && modelEnd.getUsage() != null) {
            details.put("inputTokens", modelEnd.getUsage().getInputTokens());
            details.put("outputTokens", modelEnd.getUsage().getOutputTokens());
        } else if (event instanceof ToolCallStartEvent toolStart) {
            details.put("toolName", toolStart.getToolCallName());
        } else if (event instanceof ToolResultEndEvent toolEnd) {
            details.put("toolName", toolEnd.getToolCallName());
            details.put("toolState", toolEnd.getState().name());
        } else if (event instanceof AgentResultEvent resultEvent
                && resultEvent.getResult() != null
                && resultEvent.getResult().getGenerateReason() != null) {
            details.put("generateReason", resultEvent.getResult().getGenerateReason().name());
        }
        publish(event.getType().getValue(), details);
    }

    private void publish(String stage, Map<String, Object> details) {
        publisher.publish(new AgentProgressEvent(
                request.workflowId(), request.executionId(), request.agentType(), stage, Instant.now(), details));
    }
}
