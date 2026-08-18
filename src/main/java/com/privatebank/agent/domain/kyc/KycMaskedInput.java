package com.privatebank.agent.domain.kyc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The only customer-data object permitted to cross the model boundary. */
public record KycMaskedInput(
        Map<String, Object> payload,
        Map<String, Long> evidenceReferences,
        Set<String> managerEvidenceRefs,
        Set<String> prohibitedTerms,
        Map<String, String> aliasMappings,
        String sha256) {

    public KycMaskedInput {
        payload = freezeMap(payload);
        evidenceReferences = Collections.unmodifiableMap(new LinkedHashMap<>(evidenceReferences));
        managerEvidenceRefs = Collections.unmodifiableSet(new LinkedHashSet<>(managerEvidenceRefs));
        prohibitedTerms = Collections.unmodifiableSet(new LinkedHashSet<>(prohibitedTerms));
        aliasMappings = Collections.unmodifiableMap(new LinkedHashMap<>(aliasMappings));
    }

    public KycMaskedInput(
            Map<String, Object> payload,
            Map<String, Long> evidenceReferences,
            Set<String> prohibitedTerms,
            Map<String, String> aliasMappings,
            String sha256) {
        this(payload, evidenceReferences, Set.of(), prohibitedTerms, aliasMappings, sha256);
    }

    public KycMaskedInput(
            Map<String, Object> payload,
            Map<String, Long> evidenceReferences,
            Set<String> prohibitedTerms,
            String sha256) {
        this(payload, evidenceReferences, Set.of(), prohibitedTerms, Map.of(), sha256);
    }

    public Set<String> allowedEvidenceRefs() {
        Set<String> allowed = new LinkedHashSet<>(evidenceReferences.keySet());
        allowed.addAll(managerEvidenceRefs);
        return Collections.unmodifiableSet(allowed);
    }

    private static Map<String, Object> freezeMap(Map<String, ?> source) {
        Map<String, Object> frozen = new LinkedHashMap<>();
        source.forEach((key, value) -> frozen.put(key, freeze(value)));
        return Collections.unmodifiableMap(frozen);
    }

    private static Object freeze(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> frozen = new LinkedHashMap<>();
            map.forEach((key, item) -> frozen.put(String.valueOf(key), freeze(item)));
            return Collections.unmodifiableMap(frozen);
        }
        if (value instanceof List<?> list) {
            List<Object> frozen = new ArrayList<>(list.size());
            list.forEach(item -> frozen.add(freeze(item)));
            return Collections.unmodifiableList(frozen);
        }
        if (value instanceof Set<?> set) {
            Set<Object> frozen = new LinkedHashSet<>();
            set.forEach(item -> frozen.add(freeze(item)));
            return Collections.unmodifiableSet(frozen);
        }
        if (value instanceof Date date) {
            return date.toInstant();
        }
        return value;
    }
}
