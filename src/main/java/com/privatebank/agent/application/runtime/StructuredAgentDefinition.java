package com.privatebank.agent.application.runtime;

import io.agentscope.core.tool.Toolkit;

public record StructuredAgentDefinition<O>(
        String name,
        String systemPrompt,
        String userPrompt,
        Class<O> outputType,
        int maxIterations,
        Toolkit toolkit) {

    public StructuredAgentDefinition(
            String name,
            String systemPrompt,
            String userPrompt,
            Class<O> outputType,
            int maxIterations) {
        this(name, systemPrompt, userPrompt, outputType, maxIterations, null);
    }
}
