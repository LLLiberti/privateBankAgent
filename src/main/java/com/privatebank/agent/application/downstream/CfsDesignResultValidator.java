package com.privatebank.agent.application.downstream;

import com.privatebank.agent.domain.downstream.CfsDesignResult;
import com.privatebank.agent.domain.downstream.ProductKnowledgeEvidence;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Validates that a CFS result carries the mandatory fields and structure
 * required by the downstream compliance check and report rendering.
 */
@Component
public class CfsDesignResultValidator {

    public void validate(CfsDesignResult result) {
        if (result == null) {
            throw new IllegalArgumentException("CFS生成结果为空");
        }
        requireText(result.customerId(), "customerId");
        if (result.inputArtifactRefs() == null) {
            throw new IllegalArgumentException("CFS缺少上游Artifact引用 inputArtifactRefs");
        }
        requireText(result.inputArtifactRefs().kyc(), "inputArtifactRefs.kyc");
        requireText(result.inputArtifactRefs().market(), "inputArtifactRefs.market");
        requireText(result.inputArtifactRefs().kyp(), "inputArtifactRefs.kyp");
        if (result.cfsVersion() <= 0) {
            throw new IllegalArgumentException("CFS缺少有效版本 cfsVersion");
        }
        requireText(result.marketingStrategy(), "marketingStrategy");
        requireText(result.communicationGuide(), "communicationGuide");
        requireText(result.comprehensiveRiskAssessment(), "comprehensiveRiskAssessment");
        if (result.cfsStructure() == null) {
            throw new IllegalArgumentException("CFS缺少3+6结构 cfsStructure");
        }
        requireText(result.cfsStructure().chapter1CustomerInfo(), "cfsStructure.chapter1CustomerInfo");
        requireText(result.cfsStructure().chapter2ServicePlan(), "cfsStructure.chapter2ServicePlan");
        requireText(result.cfsStructure().chapter3MarketingStrategy(), "cfsStructure.chapter3MarketingStrategy");
        requireNonNull(result.cfsStructure().attachments(), "cfsStructure.attachments");
        requireNonNull(result.pendingVerificationItems(), "pendingVerificationItems");
        requireNonNull(result.estimatedDataItems(), "estimatedDataItems");
        requireNonNull(result.sourceRefs(), "sourceRefs");
        requireNonNull(result.productEvidenceRefs(), "productEvidenceRefs");
        requireNonNull(result.ruleRefs(), "ruleRefs");
        for (int i = 0; i < result.productEvidenceRefs().size(); i++) {
            ProductKnowledgeEvidence evidence = result.productEvidenceRefs().get(i);
            if (evidence == null) {
                throw new IllegalArgumentException("productEvidenceRefs[" + i + "] 不能为 null");
            }
            requireText(evidence.chunkId(), "productEvidenceRefs[" + i + "].chunkId");
            requireText(evidence.documentId(), "productEvidenceRefs[" + i + "].documentId");
            requireText(evidence.productId(), "productEvidenceRefs[" + i + "].productId");
            requireText(evidence.sourceId(), "productEvidenceRefs[" + i + "].sourceId");
            requireText(evidence.content(), "productEvidenceRefs[" + i + "].content");
        }
    }

    private void requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("CFS缺少 " + field);
        }
    }

    private void requireNonNull(Object value, String field) {
        if (value == null) {
            throw new IllegalArgumentException("CFS缺少 " + field);
        }
    }
}
