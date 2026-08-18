package com.privatebank.agent.application.downstream;

import com.privatebank.agent.domain.downstream.MarketInsightResult;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@Component
public class MarketInsightResultValidator {

    public void validate(MarketInsightResult result) {
        if (result == null) {
            throw new IllegalArgumentException("市场洞察结果为空");
        }
        requireText(result.customerId(), "customerId");
        requireText(result.kycArtifactRef(), "kycArtifactRef");
        requireNonNull(result.industryInsights(), "industryInsights");
        requireNonNull(result.competitorInsights(), "competitorInsights");
        requireNonNull(result.bankAdvantageMappings(), "bankAdvantageMappings");
        requireNonNull(result.differentiatedViews(), "differentiatedViews");
        requireNonNull(result.marketingOpportunities(), "marketingOpportunities");
        requireNonNull(result.riskFlags(), "riskFlags");
        requireNonNull(result.unresolvedItems(), "unresolvedItems");
        requireNonNull(result.sourceRefs(), "sourceRefs");
        requireObjectList(result.industryInsights(), "industryInsights");
        requireObjectList(result.competitorInsights(), "competitorInsights");
        requireObjectList(result.bankAdvantageMappings(), "bankAdvantageMappings");
        requireObjectList(result.differentiatedViews(), "differentiatedViews");
        requireObjectList(result.marketingOpportunities(), "marketingOpportunities");
        requireObjectList(result.riskFlags(), "riskFlags");
        requireObjectList(result.unresolvedItems(), "unresolvedItems");
    }

    private void requireObjectList(List<Map<String, Object>> items, String field) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) == null) {
                throw new IllegalArgumentException(field + "[" + i + "] 不能为 null");
            }
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
