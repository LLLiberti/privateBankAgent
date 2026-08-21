package com.privatebank.agent.domain.downstream;

import java.util.List;

public record ComplianceCheckResult(
        String cfsArtifactRef,
        String complianceResult,
        String checkSummary,
        List<Finding> findings,
        List<String> conclusionExplanations,
        List<String> evidenceChain,
        List<String> reviewRequiredItems) {

    public record Finding(
            String location,
            String ruleId,
            String severity,
            String message,
            List<String> evidenceRefs,
            String suggestion) {
    }
}
