package com.privatebank.agent.domain.downstream;

public record ComplianceCheckInput(
        String workflowId,
        String cfsArtifactId,
        String cfsResultJson,
        String checkStage,
        String ruleSetVersion,
        String cfsTemplateVersion) {
}
