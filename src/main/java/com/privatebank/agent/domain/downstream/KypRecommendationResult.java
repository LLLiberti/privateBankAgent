package com.privatebank.agent.domain.downstream;

import java.util.List;

public record KypRecommendationResult(
        String mode,
        String customerId,
        String kycArtifactRef,
        List<RecommendedItem> recommendedItems,
        List<RejectedItem> rejectedItems,
        List<ReviewRequiredItem> reviewRequiredItems,
        List<RuleCheckResult> ruleCheckResults,
        List<String> unresolvedItems,
        List<ProductKnowledgeEvidence> productEvidenceRefs) {

    public record RecommendedItem(
            String productId,
            String productName,
            String reason,
            List<String> limitations,
            List<String> evidenceRefs) {
    }

    public record RejectedItem(
            String productId,
            String productName,
            String reason,
            String ruleId) {
    }

    public record ReviewRequiredItem(
            String productId,
            String productName,
            String reason) {
    }

    public record RuleCheckResult(
            String ruleId,
            String productId,
            boolean passed,
            String message) {
    }
}
