package com.privatebank.business.service.workflow;

import com.privatebank.agent.application.runtime.AgentExecutionCompletedEvent;
import com.privatebank.agent.application.runtime.AgentExecutionFailedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.stereotype.Component;

import java.sql.SQLTransientConnectionException;

/**
 * Synchronously persists an Agent outcome before control returns to the Agent executor.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowAgentResultEventDispatcher {

    private static final int MAX_ATTEMPTS = 3;
    private static final long[] RETRY_DELAYS_MILLIS = {200L, 1_000L};

    private final WorkflowAgentResultListener resultListener;

    @EventListener
    public void onAgentCompleted(AgentExecutionCompletedEvent event) {
        dispatch(event.workflowId(), event.agentType().name(), () -> resultListener.onAgentCompleted(event));
    }

    @EventListener
    public void onAgentFailed(AgentExecutionFailedEvent event) {
        dispatch(event.workflowId(), event.agentType().name(), () -> resultListener.onAgentFailed(event));
    }

    private void dispatch(String workflowId, String agentType, Runnable action) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                action.run();
                return;
            } catch (RuntimeException exception) {
                if (!isConnectionAcquisitionFailure(exception) || attempt == MAX_ATTEMPTS) {
                    throw exception;
                }
                long delayMillis = RETRY_DELAYS_MILLIS[attempt - 1];
                log.warn(
                        "Workflow result handling could not acquire a database connection; "
                                + "workflowId={}, agentType={}, attempt={}/{}, retryInMs={}",
                        workflowId,
                        agentType,
                        attempt,
                        MAX_ATTEMPTS,
                        delayMillis);
                waitBeforeRetry(delayMillis);
            }
        }
    }

    private boolean isConnectionAcquisitionFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof CannotGetJdbcConnectionException
                    || current instanceof SQLTransientConnectionException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void waitBeforeRetry(long delayMillis) {
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Workflow result retry was interrupted", exception);
        }
    }
}
