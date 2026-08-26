package com.privatebank.business.service.report;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record CfsReportDocument(
        String workflowId,
        String cfsArtifactId,
        String complianceArtifactId,
        String customerId,
        int cfsVersion,
        OffsetDateTime generatedAt,
        String chapter1CustomerInfo,
        String chapter2ServicePlan,
        String chapter3MarketingStrategy,
        String marketingStrategy,
        String communicationGuide,
        String comprehensiveRiskAssessment,
        List<String> attachments,
        List<String> pendingVerificationItems,
        List<String> estimatedDataItems,
        List<String> sourceRefs,
        List<String> productEvidenceRefs,
        List<String> ruleRefs,
        Map<String, String> inputArtifactRefs,
        List<DataSourceItem> dataSources) {

    public record DataSourceItem(
            String sourceType,
            String sourceName,
            String locator,
            String sourceDate,
            String summary,
            String sourceLevel) {
    }
}
