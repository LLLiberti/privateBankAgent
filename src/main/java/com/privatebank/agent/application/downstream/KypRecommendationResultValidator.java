package com.privatebank.agent.application.downstream;

import com.privatebank.agent.domain.downstream.KypRecommendationResult;
import com.privatebank.agent.domain.downstream.ProductKnowledgeEvidence;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class KypRecommendationResultValidator {

    public void validate(KypRecommendationResult result) {
        if (result == null) {
            throw new IllegalArgumentException("产品专家结果为空");
        }
        requireText(result.mode(), "mode");
        requireText(result.customerId(), "customerId");
        requireText(result.kycArtifactRef(), "kycArtifactRef");
        requireNonNull(result.recommendedItems(), "recommendedItems");
        requireNonNull(result.rejectedItems(), "rejectedItems");
        requireNonNull(result.reviewRequiredItems(), "reviewRequiredItems");
        requireNonNull(result.ruleCheckResults(), "ruleCheckResults");
        requireNonNull(result.unresolvedItems(), "unresolvedItems");
        requireNonNull(result.productEvidenceRefs(), "productEvidenceRefs");

        if (!result.recommendedItems().isEmpty() && result.productEvidenceRefs().isEmpty()) {
            throw new IllegalArgumentException("存在推荐项但 productEvidenceRefs 为空，推荐必须引用产品证据");
        }

        for (int i = 0; i < result.recommendedItems().size(); i++) {
            KypRecommendationResult.RecommendedItem item = result.recommendedItems().get(i);
            if (item == null) {
                throw new IllegalArgumentException("recommendedItems[" + i + "] 不能为 null");
            }
            requireText(item.productId(), "recommendedItems[" + i + "].productId");
            requireText(item.productName(), "recommendedItems[" + i + "].productName");
            requireText(item.reason(), "recommendedItems[" + i + "].reason");
            requireNonNull(item.limitations(), "recommendedItems[" + i + "].limitations");
            requireNonNull(item.evidenceRefs(), "recommendedItems[" + i + "].evidenceRefs");
            if (item.evidenceRefs().isEmpty()) {
                throw new IllegalArgumentException("recommendedItems[" + i + "].evidenceRefs 不能为空，推荐必须引用产品证据");
            }
        }
        for (int i = 0; i < result.rejectedItems().size(); i++) {
            KypRecommendationResult.RejectedItem item = result.rejectedItems().get(i);
            if (item == null) {
                throw new IllegalArgumentException("rejectedItems[" + i + "] 不能为 null");
            }
            requireText(item.productId(), "rejectedItems[" + i + "].productId");
            requireText(item.productName(), "rejectedItems[" + i + "].productName");
            requireText(item.reason(), "rejectedItems[" + i + "].reason");
            requireText(item.ruleId(), "rejectedItems[" + i + "].ruleId");
        }
        for (int i = 0; i < result.reviewRequiredItems().size(); i++) {
            KypRecommendationResult.ReviewRequiredItem item = result.reviewRequiredItems().get(i);
            if (item == null) {
                throw new IllegalArgumentException("reviewRequiredItems[" + i + "] 不能为 null");
            }
            requireText(item.productId(), "reviewRequiredItems[" + i + "].productId");
            requireText(item.productName(), "reviewRequiredItems[" + i + "].productName");
            requireText(item.reason(), "reviewRequiredItems[" + i + "].reason");
        }
        for (int i = 0; i < result.ruleCheckResults().size(); i++) {
            KypRecommendationResult.RuleCheckResult item = result.ruleCheckResults().get(i);
            if (item == null) {
                throw new IllegalArgumentException("ruleCheckResults[" + i + "] 不能为 null");
            }
            requireText(item.ruleId(), "ruleCheckResults[" + i + "].ruleId");
            requireText(item.productId(), "ruleCheckResults[" + i + "].productId");
            requireText(item.message(), "ruleCheckResults[" + i + "].message");
        }
        for (int i = 0; i < result.productEvidenceRefs().size(); i++) {
            ProductKnowledgeEvidence item = result.productEvidenceRefs().get(i);
            if (item == null) {
                throw new IllegalArgumentException("productEvidenceRefs[" + i + "] 不能为 null");
            }
            requireText(item.chunkId(), "productEvidenceRefs[" + i + "].chunkId");
            requireText(item.documentId(), "productEvidenceRefs[" + i + "].documentId");
            requireText(item.productId(), "productEvidenceRefs[" + i + "].productId");
            requireText(item.sourceId(), "productEvidenceRefs[" + i + "].sourceId");
            requireText(item.content(), "productEvidenceRefs[" + i + "].content");
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
