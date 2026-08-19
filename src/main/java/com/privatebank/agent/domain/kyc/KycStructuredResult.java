package com.privatebank.agent.domain.kyc;

import java.util.List;

public record KycStructuredResult(
        RiskLevel riskLevel,
        String summary,
        List<Finding> findings,
        List<String> riskAlerts,
        List<String> recommendedActions,
        List<String> dataGaps,
        GraphAssessment graphAssessment,
        List<FollowUpQuestion> followUpQuestions) {

    public KycStructuredResult {
        followUpQuestions = followUpQuestions == null ? List.of() : List.copyOf(followUpQuestions);
    }

    public KycStructuredResult(
            RiskLevel riskLevel,
            String summary,
            List<Finding> findings,
            List<String> riskAlerts,
            List<String> recommendedActions,
            List<String> dataGaps,
            GraphAssessment graphAssessment) {
        this(riskLevel, summary, findings, riskAlerts, recommendedActions, dataGaps,
                graphAssessment, List.of());
    }

    public record Finding(
            Dimension dimension,
            RiskLevel riskLevel,
            String finding,
            List<String> evidenceRefs) {
    }

    public record GraphAssessment(
            GraphContribution contribution,
            String summary,
            List<String> evidenceRefs) {
    }

    public record FollowUpQuestion(
            String id,
            String question) {
    }

    public enum RiskLevel {
        LOW, MEDIUM, HIGH, UNKNOWN
    }

    public enum Dimension {
        PERSON, ENTERPRISE, FAMILY, SOCIAL
    }

    public enum GraphContribution {
        INCREMENTAL, CONFIRMATORY, NO_INCREMENT, NOT_AVAILABLE
    }
}
