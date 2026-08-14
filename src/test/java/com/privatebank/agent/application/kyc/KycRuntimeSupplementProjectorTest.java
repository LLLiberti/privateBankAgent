package com.privatebank.agent.application.kyc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.agent.domain.kyc.KycCustomerData;
import com.privatebank.agent.domain.kyc.KycMaskedInput;
import com.privatebank.business.dto.customer.CustomerSummaryResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KycRuntimeSupplementProjectorTest {

    @Test
    void projectsManagerTextToRuntimeSignalsWithoutSendingRawTextToTheModel() throws Exception {
        String rawSupplement = "客户近期存在跨境配置与流动性安排需求";
        KycRuntimeSupplement supplement = new KycRuntimeSupplementProjector()
                .project(rawSupplement, List.of("跨境配置", "流动性"));
        KycMaskedInput input = new KycDataMaskingService(new ObjectMapper())
                .mask(emptyData(), supplement);

        assertThat(supplement.signals()).contains("CROSS_BORDER_NEED", "LIQUIDITY_NEED");
        String modelPayload = new ObjectMapper().writeValueAsString(input.payload());
        assertThat(modelPayload).contains("CROSS_BORDER_NEED", "LIQUIDITY_NEED")
                .doesNotContain(rawSupplement, "跨境配置", "流动性安排");
    }

    private KycCustomerData emptyData() {
        return new KycCustomerData(
                new CustomerSummaryResponse(1L, "张三", null, "ENTREPRENEUR", "VERIFIED", "UNKNOWN"),
                Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
    }
}
