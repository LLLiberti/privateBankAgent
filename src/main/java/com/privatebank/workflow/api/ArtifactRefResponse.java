package com.privatebank.workflow.api;

import com.privatebank.workflow.domain.AgentArtifact;
import com.privatebank.workflow.domain.AgentType;

import java.time.LocalDateTime;

public record ArtifactRefResponse(
        String artifactId,
        AgentType agentType,
        String executionId,
        Integer version,
        String complianceResult,
        String storageKey,
        LocalDateTime createdAt) {

    public static ArtifactRefResponse from(AgentArtifact artifact) {
        return new ArtifactRefResponse(
                artifact.getArtifactId(), artifact.getAgentType(), artifact.getExecutionId(), artifact.getVersion(),
                artifact.getComplianceResult(), artifact.getStorageKey(), artifact.getCreateTime());
    }
}
