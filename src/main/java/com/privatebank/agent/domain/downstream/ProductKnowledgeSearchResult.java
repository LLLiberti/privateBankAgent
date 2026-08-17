package com.privatebank.agent.domain.downstream;

import java.util.List;

public record ProductKnowledgeSearchResult(
        List<String> candidateProductIds,
        List<ProductKnowledgeEvidence> evidence) {

    public ProductKnowledgeSearchResult {
        candidateProductIds = candidateProductIds == null ? List.of() : List.copyOf(candidateProductIds);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public static ProductKnowledgeSearchResult empty() {
        return new ProductKnowledgeSearchResult(List.of(), List.of());
    }
}
