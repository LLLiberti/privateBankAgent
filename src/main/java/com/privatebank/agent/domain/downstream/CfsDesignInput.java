package com.privatebank.agent.domain.downstream;

public record CfsDesignInput(
        String workflowId,
        String kycArtifactId,
        String marketArtifactId,
        String kypArtifactId,
        String kycResultJson,
        String marketResultJson,
        String kypResultJson,
        String cfsTemplateVersion,
        String generationMode,
        String previousCfsArtifactId,
        String revisionInstruction) {
}
