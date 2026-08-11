package com.privatebank.business.service.kyc;

public record KycGenerationResult(String analysisJson, int attempts, String modelName) {
}
