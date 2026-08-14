package com.privatebank.agent.application.runtime;

import com.privatebank.business.enums.workflow.AgentType;

public interface BusinessAgentExecutor<I, O> {

    AgentType agentType();

    AgentExecutionResult<O> execute(AgentExecutionRequest<I> request);
}
