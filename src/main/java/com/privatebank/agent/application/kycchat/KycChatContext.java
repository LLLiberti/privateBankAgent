package com.privatebank.agent.application.kycchat;

import java.util.Map;

/** Read-only KYC context bound to one workflow, artifact and person. */
public record KycChatContext(
        String workflowId,
        Long personId,
        String kycArtifactId,
        String kycAnalysisJson,
        String maskedInputSha256,
        Map<String, String> aliasMappings) {

    public KycChatContext {
        aliasMappings = Map.copyOf(aliasMappings);
    }
}
