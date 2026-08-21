package com.privatebank.agent.domain.downstream;

import java.util.List;

public record ProductRetrievalIssue(
        String stage,
        String code,
        String message,
        List<String> affectedProductIds) {

    public ProductRetrievalIssue {
        affectedProductIds = affectedProductIds == null ? List.of() : List.copyOf(affectedProductIds);
    }
}
