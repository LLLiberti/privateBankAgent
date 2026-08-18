package com.privatebank.agent.application.kyc;

import java.util.List;

/** Process-local manager context; it is masked before crossing the model boundary. */
public record KycRuntimeSupplement(String description, List<String> confirmedItems) {

    public KycRuntimeSupplement {
        description = description == null || description.isBlank() ? null : description.trim();
        confirmedItems = confirmedItems == null
                ? List.of()
                : confirmedItems.stream()
                        .filter(item -> item != null && !item.isBlank())
                        .map(String::trim)
                        .distinct()
                        .toList();
    }

    public static KycRuntimeSupplement empty() {
        return new KycRuntimeSupplement(null, List.of());
    }

    public boolean isEmpty() {
        return description == null && confirmedItems.isEmpty();
    }
}
