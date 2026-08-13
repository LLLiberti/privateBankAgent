package com.privatebank.agent.application.runtime;

public record AgentExecutionResult<O>(
        O output,
        int attempts,
        String modelName) {
}
