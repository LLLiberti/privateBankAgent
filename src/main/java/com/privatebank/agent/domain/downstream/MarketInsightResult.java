package com.privatebank.agent.domain.downstream;

import java.util.List;
import java.util.Map;

public record MarketInsightResult(
        String customerId,
        String kycArtifactRef,
        List<Map<String, Object>> industryInsights,
        List<Map<String, Object>> competitorInsights,
        List<Map<String, Object>> bankAdvantageMappings,
        List<Map<String, Object>> differentiatedViews,
        List<Map<String, Object>> marketingOpportunities,
        List<Map<String, Object>> riskFlags,
        List<Map<String, Object>> unresolvedItems,
        List<String> sourceRefs) {
}
