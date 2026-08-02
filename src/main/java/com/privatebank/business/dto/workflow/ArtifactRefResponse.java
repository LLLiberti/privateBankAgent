package com.privatebank.business.dto.workflow;

import com.privatebank.business.entity.workflow.AgentArtifact;
import com.privatebank.business.entity.workflow.AgentType;

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
