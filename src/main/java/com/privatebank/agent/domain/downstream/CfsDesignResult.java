package com.privatebank.agent.domain.downstream;

import java.util.List;

public record CfsDesignResult(
        String customerId,
        InputArtifactRefs inputArtifactRefs,
        int cfsVersion,
        String marketingStrategy,
        String communicationGuide,
        String comprehensiveRiskAssessment,
        CfsStructure cfsStructure,
        List<String> pendingVerificationItems,
        List<String> estimatedDataItems,
        List<String> sourceRefs,
        List<ProductKnowledgeEvidence> productEvidenceRefs,
        List<String> ruleRefs) {

    public record InputArtifactRefs(
            String kyc,
            String market,
            String kyp) {
    }

    public record CfsStructure(
            String chapter1CustomerInfo,
            String chapter2ServicePlan,
            String chapter3MarketingStrategy,
            List<String> attachments) {
    }
}
