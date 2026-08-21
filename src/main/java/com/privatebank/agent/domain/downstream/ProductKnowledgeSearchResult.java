package com.privatebank.agent.domain.downstream;

import java.util.List;

public record ProductKnowledgeSearchResult(
        List<String> candidateProductIds,
        List<ProductKnowledgeEvidence> evidence,
        List<ProductRetrievalIssue> retrievalIssues) {

    public ProductKnowledgeSearchResult {
        candidateProductIds = candidateProductIds == null ? List.of() : List.copyOf(candidateProductIds);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        retrievalIssues = retrievalIssues == null ? List.of() : List.copyOf(retrievalIssues);
    }

    public ProductKnowledgeSearchResult(
            List<String> candidateProductIds,
            List<ProductKnowledgeEvidence> evidence) {
        this(candidateProductIds, evidence, List.of());
    }

    public static ProductKnowledgeSearchResult empty() {
        return new ProductKnowledgeSearchResult(List.of(), List.of(), List.of());
    }
}
