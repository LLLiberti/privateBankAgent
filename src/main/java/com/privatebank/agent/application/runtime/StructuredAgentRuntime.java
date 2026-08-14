package com.privatebank.agent.application.runtime;

public interface StructuredAgentRuntime {

    <I, O> AgentExecutionResult<O> execute(
            AgentExecutionRequest<I> request,
            StructuredAgentDefinition<O> definition);
}
