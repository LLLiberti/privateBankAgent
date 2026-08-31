package com.privatebank.agent.application.kyc;

import com.privatebank.business.dto.workflow.KycQaItem;

import java.util.List;

/** Process-local manager context; it is masked before crossing the model boundary. */
public record KycRuntimeSupplement(
        String description,
        List<String> confirmedItems,
        List<KycQaItem> qaItems) {

    public KycRuntimeSupplement {
        description = description == null || description.isBlank() ? null : description.trim();
        confirmedItems = confirmedItems == null
                ? List.of()
                : confirmedItems.stream()
                        .filter(item -> item != null && !item.isBlank())
                        .map(String::trim)
                        .distinct()
                        .toList();
        qaItems = qaItems == null ? List.of() : List.copyOf(qaItems);
    }

    public KycRuntimeSupplement(String description, List<String> confirmedItems) {
        this(description, confirmedItems, List.of());
    }

    public static KycRuntimeSupplement empty() {
        return new KycRuntimeSupplement(null, List.of(), List.of());
    }

    public boolean isEmpty() {
        return description == null && confirmedItems.isEmpty() && qaItems.isEmpty();
    }
}
