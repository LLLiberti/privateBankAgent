package com.privatebank.agent.domain.kyc;

public record KycGenerationResult(String analysisJson, int attempts, String modelName) {
}
