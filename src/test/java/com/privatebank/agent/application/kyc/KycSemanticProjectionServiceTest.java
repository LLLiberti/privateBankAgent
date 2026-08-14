package com.privatebank.agent.application.kyc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KycSemanticProjectionServiceTest {

    private final KycSemanticProjectionService service = new KycSemanticProjectionService();

    @Test
    void doesNotMatchAsciiKeywordsInsideWords() {
        assertThat(service.interactionTopics("chairman")).doesNotContain("DIGITAL_TECHNOLOGY");
        assertThat(service.businessCategories("retail services")).doesNotContain("ARTIFICIAL_INTELLIGENCE");
        assertThat(service.businessCategories("AI platform")).contains("ARTIFICIAL_INTELLIGENCE");
    }

    @Test
    void doesNotTreatProvinceNamesAsCloudComputing() {
        assertThat(service.businessCategories("云南文旅项目")).doesNotContain("CLOUD_COMPUTING");
        assertThat(service.businessCategories("企业云计算服务")).contains("CLOUD_COMPUTING");
    }
}
