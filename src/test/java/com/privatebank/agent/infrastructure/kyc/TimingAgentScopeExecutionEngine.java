package com.privatebank.agent.infrastructure.kyc;

import com.privatebank.agent.application.runtime.AgentExecutionRequest;
import com.privatebank.agent.application.runtime.AgentExecutionResult;
import com.privatebank.agent.application.runtime.StructuredAgentDefinition;
import com.privatebank.agent.application.runtime.StructuredAgentRuntime;
import com.privatebank.business.enums.workflow.AgentType;

import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test-only wrapper around {@link StructuredAgentRuntime} that records how long
 * each model/runtime execution takes, and prints detailed failure diagnostics
 * when a live model/runtime call fails. Live tests can use it to separate model
 * execution time from deterministic program execution time and to diagnose
 * "KYC 模型调用失败" style errors.
 */
public class TimingAgentScopeExecutionEngine implements StructuredAgentRuntime {

    private final StructuredAgentRuntime delegate;
    private final List<ModelExecutionTiming> timings = new CopyOnWriteArrayList<>();

    public TimingAgentScopeExecutionEngine(StructuredAgentRuntime delegate) {
        this.delegate = delegate;
    }

    @Override
    public <I, O> AgentExecutionResult<O> execute(
            AgentExecutionRequest<I> request,
            StructuredAgentDefinition<O> definition) {
        long started = System.nanoTime();
        try {
            return delegate.execute(request, definition);
        } catch (RuntimeException | Error exception) {
            logFailure(request, definition, exception);
            throw exception;
        } finally {
            long modelMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            timings.add(new ModelExecutionTiming(
                    request.workflowId(),
                    request.executionId(),
                    request.agentType(),
                    modelMs));
        }
    }

    private void logFailure(
            AgentExecutionRequest<?> request,
            StructuredAgentDefinition<?> definition,
            Throwable failure) {
        System.out.printf("[TimingAgentScopeExecutionEngine] model/runtime execution failed"
                        + " workflowId=%s executionId=%s agentType=%s agentName=%s%n",
                request.workflowId(), request.executionId(), request.agentType(), definition.name());
        Throwable current = failure;
        int depth = 0;
        while (current != null && depth < 20) {
            System.out.printf("  cause[%d] %s: %s%n", depth,
                    current.getClass().getName(), current.getMessage());
            current = current.getCause();
            depth++;
        }
        failure.printStackTrace(System.out);
    }

    public List<ModelExecutionTiming> timings() {
        return List.copyOf(timings);
    }

    public OptionalLong modelMs(String workflowId, String executionId) {
        return timings.stream()
                .filter(timing -> timing.workflowId().equals(workflowId)
                        && timing.executionId().equals(executionId))
                .mapToLong(ModelExecutionTiming::modelMs)
                .findFirst();
    }

    public record ModelExecutionTiming(
            String workflowId,
            String executionId,
            AgentType agentType,
            long modelMs) {
    }
}
