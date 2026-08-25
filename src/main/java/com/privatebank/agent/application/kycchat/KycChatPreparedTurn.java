package com.privatebank.agent.application.kycchat;

import java.util.Map;

/** Masked, alias-normalized data prepared before a chat turn starts. */
public record KycChatPreparedTurn(
        String maskedMessage,
        Map<String, Object> currentMaskedData,
        String snapshotComparison,
        Map<String, String> aliasMappings) {

    public KycChatPreparedTurn {
        currentMaskedData = Map.copyOf(currentMaskedData);
        aliasMappings = Map.copyOf(aliasMappings);
    }
}
