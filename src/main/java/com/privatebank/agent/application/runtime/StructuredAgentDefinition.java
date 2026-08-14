package com.privatebank.agent.application.runtime;

public record StructuredAgentDefinition<O>(
        String name,
        String systemPrompt,
        String userPrompt,
        Class<O> outputType,
        int maxIterations) {
}
