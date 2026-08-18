package com.privatebank.agent.application.downstream;

import com.privatebank.agent.domain.downstream.ComplianceCheckResult;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Set;

@Component
public class ComplianceCheckResultValidator {

    private static final Set<String> ALLOWED_RESULTS = Set.of("PASS", "REJECT", "REVIEW_REQUIRED");

    public void validate(ComplianceCheckResult result) {
        if (result == null) {
            throw new IllegalArgumentException("合规检查结果为空");
        }
        requireText(result.cfsArtifactRef(), "cfsArtifactRef");
        requireText(result.complianceResult(), "complianceResult");
        if (!ALLOWED_RESULTS.contains(result.complianceResult())) {
            throw new IllegalArgumentException("complianceResult 必须是 PASS、REJECT 或 REVIEW_REQUIRED");
        }
        requireText(result.checkSummary(), "checkSummary");
        requireNonNull(result.findings(), "findings");
        requireNonNull(result.conclusionExplanations(), "conclusionExplanations");
        requireNonNull(result.evidenceChain(), "evidenceChain");
        requireNonNull(result.reviewRequiredItems(), "reviewRequiredItems");

        for (int i = 0; i < result.findings().size(); i++) {
            ComplianceCheckResult.Finding finding = result.findings().get(i);
            if (finding == null) {
                throw new IllegalArgumentException("findings[" + i + "] 不能为 null");
            }
            requireText(finding.location(), "findings[" + i + "].location");
            requireText(finding.ruleId(), "findings[" + i + "].ruleId");
            requireText(finding.severity(), "findings[" + i + "].severity");
            requireText(finding.message(), "findings[" + i + "].message");
            requireNonNull(finding.evidenceRefs(), "findings[" + i + "].evidenceRefs");
            requireText(finding.suggestion(), "findings[" + i + "].suggestion");
        }
    }

    private void requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }

    private void requireNonNull(Object value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " 不能为 null");
        }
    }
}
