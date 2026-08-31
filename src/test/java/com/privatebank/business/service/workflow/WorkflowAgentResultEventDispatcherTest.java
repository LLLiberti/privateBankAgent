package com.privatebank.business.service.workflow;

import com.privatebank.agent.application.runtime.AgentExecutionCompletedEvent;
import com.privatebank.business.enums.workflow.AgentType;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

import java.sql.SQLTransientConnectionException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class WorkflowAgentResultEventDispatcherTest {

    @Test
    void retriesTransientConnectionFailureUntilResultTransactionSucceeds() {
        WorkflowAgentResultListener listener = mock(WorkflowAgentResultListener.class);
        WorkflowAgentResultEventDispatcher dispatcher = new WorkflowAgentResultEventDispatcher(listener);
        AgentExecutionCompletedEvent event = completedEvent();
        CannotGetJdbcConnectionException failure = new CannotGetJdbcConnectionException(
                "connection unavailable", new SQLTransientConnectionException("pool timeout"));
        doThrow(failure).doThrow(failure).doNothing().when(listener).onAgentCompleted(event);

        dispatcher.onAgentCompleted(event);

        verify(listener, times(3)).onAgentCompleted(event);
    }

    @Test
    void doesNotRetryNonConnectionFailure() {
        WorkflowAgentResultListener listener = mock(WorkflowAgentResultListener.class);
        WorkflowAgentResultEventDispatcher dispatcher = new WorkflowAgentResultEventDispatcher(listener);
        AgentExecutionCompletedEvent event = completedEvent();
        IllegalStateException failure = new IllegalStateException("invalid workflow state");
        doThrow(failure).when(listener).onAgentCompleted(event);

        assertThatThrownBy(() -> dispatcher.onAgentCompleted(event)).isSameAs(failure);

        verify(listener).onAgentCompleted(event);
    }

    private AgentExecutionCompletedEvent completedEvent() {
        return new AgentExecutionCompletedEvent(
                "WF-1", "AS-1", AgentType.SOLUTION_DESIGN, "EXE-1", "{}", null, 0);
    }
}
