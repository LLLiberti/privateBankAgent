package com.privatebank.agent.application.kycchat;

import java.util.List;
import java.util.Map;

/** Complete masked input for one streaming AgentScope invocation. */
public record KycChatAgentCommand(
        String sessionId,
        String turnId,
        String userId,
        String workflowId,
        Long personId,
        String kycArtifactId,
        String kycAnalysisJson,
        List<KycChatMessage> history,
        String maskedMessage,
        Map<String, Object> currentMaskedData,
        String snapshotComparison) {

    public KycChatAgentCommand {
        history = List.copyOf(history);
        currentMaskedData = Map.copyOf(currentMaskedData);
    }
}
