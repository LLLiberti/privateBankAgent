package com.privatebank.agent.domain.kyc;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** The only customer-data object permitted to cross the model boundary. */
public record KycMaskedInput(
        Map<String, Object> payload,
        Map<String, Long> evidenceReferences,
        Set<String> prohibitedTerms,
        String sha256) {

    public KycMaskedInput {
        payload = Map.copyOf(new LinkedHashMap<>(payload));
        evidenceReferences = Map.copyOf(new LinkedHashMap<>(evidenceReferences));
        prohibitedTerms = Set.copyOf(prohibitedTerms);
    }
}
