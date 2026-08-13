package com.privatebank.agent.domain.kyc;

import java.util.List;

public record KycStructuredResult(
        RiskLevel riskLevel,
        String summary,
        List<Finding> findings,
        List<String> riskAlerts,
        List<String> recommendedActions,
        List<String> dataGaps) {

    public record Finding(
            Dimension dimension,
            RiskLevel riskLevel,
            String finding,
            List<String> evidenceRefs) {
    }

    public enum RiskLevel {
        LOW, MEDIUM, HIGH, UNKNOWN
    }

    public enum Dimension {
        PERSON, ENTERPRISE, FAMILY, SOCIAL
    }
}
