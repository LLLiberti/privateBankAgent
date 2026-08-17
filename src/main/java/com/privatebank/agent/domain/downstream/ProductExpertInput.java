package com.privatebank.agent.domain.downstream;

import java.util.List;

public record ProductExpertInput(
        String workflowId,
        String kycArtifactId,
        String kycResultJson,
        List<String> candidateProductIds,
        List<ProductKnowledgeEvidence> productKnowledge) {
}
