package com.privatebank.agent.application.downstream;

import com.privatebank.agent.domain.downstream.CfsDesignResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CfsDesignResultValidatorTest {

    private final CfsDesignResultValidator validator = new CfsDesignResultValidator();

    @Test
    void acceptsNonEmptyThreeChaptersAndExactlySixAttachments() {
        assertThatCode(() -> validator.validate(validResult())).doesNotThrowAnyException();
    }

    @Test
    void rejectsBlankChapterContent() {
        CfsDesignResult valid = validResult();
        CfsDesignResult invalid = new CfsDesignResult(
                valid.customerId(), valid.inputArtifactRefs(), valid.cfsVersion(),
                valid.marketingStrategy(), valid.communicationGuide(), valid.comprehensiveRiskAssessment(),
                new CfsDesignResult.CfsStructure(
                        " ", valid.cfsStructure().chapter2ServicePlan(),
                        valid.cfsStructure().chapter3MarketingStrategy(), valid.cfsStructure().attachments()),
                valid.pendingVerificationItems(), valid.estimatedDataItems(), valid.sourceRefs(),
                valid.productEvidenceRefs(), valid.ruleRefs());

        assertThatThrownBy(() -> validator.validate(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cfsStructure.chapter1CustomerInfo");
    }

    @Test
    void rejectsWhenThreePlusSixAttachmentCountIsNotSix() {
        CfsDesignResult valid = validResult();
        CfsDesignResult invalid = withAttachments(valid, valid.cfsStructure().attachments().subList(0, 5));

        assertThatThrownBy(() -> validator.validate(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cfsStructure.attachments");
    }

    @Test
    void rejectsBlankThreePlusSixAttachment() {
        CfsDesignResult valid = validResult();
        List<String> attachments = List.of(
                "controller details", "company events and financial analysis", " ",
                "industry and competitors", "company and personal public opinion",
                "advantages and marketing language");

        assertThatThrownBy(() -> validator.validate(withAttachments(valid, attachments)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cfsStructure.attachments[2]");
    }

    private CfsDesignResult withAttachments(CfsDesignResult source, List<String> attachments) {
        return new CfsDesignResult(
                source.customerId(), source.inputArtifactRefs(), source.cfsVersion(),
                source.marketingStrategy(), source.communicationGuide(), source.comprehensiveRiskAssessment(),
                new CfsDesignResult.CfsStructure(
                        source.cfsStructure().chapter1CustomerInfo(), source.cfsStructure().chapter2ServicePlan(),
                        source.cfsStructure().chapter3MarketingStrategy(), attachments),
                source.pendingVerificationItems(), source.estimatedDataItems(), source.sourceRefs(),
                source.productEvidenceRefs(), source.ruleRefs());
    }

    private CfsDesignResult validResult() {
        return new CfsDesignResult(
                "C-1",
                new CfsDesignResult.InputArtifactRefs("ART-KYC", "ART-MARKET", "ART-KYP"),
                1,
                "strategy",
                "guide",
                "risk assessment",
                new CfsDesignResult.CfsStructure(
                        "customer information",
                        "four-dimensional service plan",
                        "marketing strategy",
                        List.of(
                                "controller details",
                                "company events and financial analysis",
                                "products and services",
                                "industry and competitors",
                                "company and personal public opinion",
                                "advantages and marketing language")),
                List.of(),
                List.of(),
                List.of("SRC-1"),
                List.of(),
                List.of("RULE-1"));
    }
}
