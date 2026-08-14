package com.privatebank.agent.application.kyc;

import java.util.LinkedHashSet;
import java.util.Set;

/** Process-local sanitized manager context; raw manager input never reaches persistence. */
public record KycRuntimeSupplement(Set<String> signals) {

    public KycRuntimeSupplement {
        signals = signals == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(signals));
    }

    public static KycRuntimeSupplement empty() {
        return new KycRuntimeSupplement(Set.of());
    }
}
