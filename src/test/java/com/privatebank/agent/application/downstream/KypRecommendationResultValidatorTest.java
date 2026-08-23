package com.privatebank.agent.application.downstream;

import com.privatebank.agent.domain.downstream.KypRecommendationResult;
import com.privatebank.agent.domain.downstream.ProductKnowledgeEvidence;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KypRecommendationResultValidatorTest {

    private final KypRecommendationResultValidator validator = new KypRecommendationResultValidator();

    @Test
    void rejectsRecommendationWithoutProductEvidenceRefs() {
        KypRecommendationResult result = result(
                List.of(recommendation(List.of("CHUNK-1"))),
                List.of());

        assertThatThrownBy(() -> validator.validate(result))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("productEvidenceRefs");
    }

    @Test
    void rejectsRecommendationWithoutItemEvidenceRefs() {
        KypRecommendationResult result = result(
                List.of(recommendation(List.of())),
                List.of(evidence()));

        assertThatThrownBy(() -> validator.validate(result))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recommendedItems[0].evidenceRefs");
    }

    @Test
    void acceptsRecommendationWithProductAndItemEvidenceRefs() {
        KypRecommendationResult result = result(
                List.of(recommendation(List.of("CHUNK-1"))),
                List.of(evidence()));

        assertThatCode(() -> validator.validate(result)).doesNotThrowAnyException();
    }

    private KypRecommendationResult result(
            List<KypRecommendationResult.RecommendedItem> recommendedItems,
            List<ProductKnowledgeEvidence> evidenceRefs) {
        return new KypRecommendationResult(
                "KYP",
                "C-1",
                "ART-KYC",
                recommendedItems,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                evidenceRefs);
    }

    private KypRecommendationResult.RecommendedItem recommendation(List<String> evidenceRefs) {
        return new KypRecommendationResult.RecommendedItem(
                "P-1",
                "产品一",
                "与客户风险等级匹配",
                List.of("需关注期限"),
                evidenceRefs);
    }

    private ProductKnowledgeEvidence evidence() {
        return new ProductKnowledgeEvidence(
                "CHUNK-1",
                "DOC-1",
                "P-1",
                "产品证据",
                "SRC-1",
                0.8);
    }
}
