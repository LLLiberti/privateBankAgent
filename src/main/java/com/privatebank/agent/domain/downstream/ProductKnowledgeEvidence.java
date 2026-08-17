package com.privatebank.agent.domain.downstream;

public record ProductKnowledgeEvidence(
        String chunkId,
        String documentId,
        String productId,
        String content,
        String sourceId,
        double score) {
}
