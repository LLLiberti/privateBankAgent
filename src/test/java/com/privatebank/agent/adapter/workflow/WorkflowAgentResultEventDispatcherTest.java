package com.privatebank.agent.adapter.workflow;

import com.privatebank.agent.domain.event.AgentSucceededEvent;
import com.privatebank.business.enums.workflow.AgentType;
import com.privatebank.business.service.workflow.WorkflowAgentResultListener;
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
        WorkflowAgentResultListener resultListener = mock(WorkflowAgentResultListener.class);
        WorkflowAgentResultEventDispatcher dispatcher =
                new WorkflowAgentResultEventDispatcher(resultListener);
        AgentSucceededEvent event = succeededEvent();
        CannotGetJdbcConnectionException connectionFailure =
                new CannotGetJdbcConnectionException(
                        "connection unavailable",
                        new SQLTransientConnectionException("pool timeout"));
        doThrow(connectionFailure)
                .doThrow(connectionFailure)
                .doNothing()
                .when(resultListener)
                .onAgentSucceeded(event);

        dispatcher.onAgentSucceeded(event);

        verify(resultListener, times(3)).onAgentSucceeded(event);
    }

    @Test
    void doesNotRetryNonConnectionFailure() {
        WorkflowAgentResultListener resultListener = mock(WorkflowAgentResultListener.class);
        WorkflowAgentResultEventDispatcher dispatcher =
                new WorkflowAgentResultEventDispatcher(resultListener);
        AgentSucceededEvent event = succeededEvent();
        IllegalStateException stateFailure = new IllegalStateException("invalid workflow state");
        doThrow(stateFailure).when(resultListener).onAgentSucceeded(event);

        assertThatThrownBy(() -> dispatcher.onAgentSucceeded(event))
                .isSameAs(stateFailure);

        verify(resultListener).onAgentSucceeded(event);
    }

    @Test
    void detectsConnectionFailureWrappedByInfrastructureException() {
        WorkflowAgentResultListener resultListener = mock(WorkflowAgentResultListener.class);
        WorkflowAgentResultEventDispatcher dispatcher =
                new WorkflowAgentResultEventDispatcher(resultListener);
        AgentSucceededEvent event = succeededEvent();
        RuntimeException wrapped = new IllegalStateException(
                "mybatis failure",
                new CannotGetJdbcConnectionException("connection unavailable"));
        doThrow(wrapped).doNothing().when(resultListener).onAgentSucceeded(event);

        dispatcher.onAgentSucceeded(event);

        verify(resultListener, times(2)).onAgentSucceeded(event);
    }

    private AgentSucceededEvent succeededEvent() {
        return new AgentSucceededEvent(
                "WF-1", "AS-1", AgentType.SOLUTION_DESIGN, "EXE-1", "ART-1");
    }
}
