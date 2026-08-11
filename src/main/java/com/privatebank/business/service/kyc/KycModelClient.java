package com.privatebank.business.service.kyc;

public interface KycModelClient {

    String generate(String systemPrompt, String userPrompt);

    default String modelName() {
        return "unknown";
    }
}
