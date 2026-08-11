package com.privatebank.agent.application.kyc;

public interface KycModelClient {

    String generate(String systemPrompt, String userPrompt);

    default String modelName() {
        return "unknown";
    }
}
