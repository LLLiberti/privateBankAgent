package com.privatebank.agent.domain.downstream;

public record MarketInsightInput(
        String workflowId,
        String kycArtifactId,
        String kycResultJson,
        String analysisScope,
        String asOfTime) {
}
