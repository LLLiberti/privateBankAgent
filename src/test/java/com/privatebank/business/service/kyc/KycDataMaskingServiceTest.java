package com.privatebank.business.service.kyc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.business.dto.customer.CustomerSummaryResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KycDataMaskingServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final KycDataMaskingService maskingService = new KycDataMaskingService(objectMapper);

    @Test
    void allowsOnlyAnalysisFieldsAndReplacesSourceIdsWithAliases() throws Exception {
        KycCustomerData data = new KycCustomerData(
                new CustomerSummaryResponse(88L, "张三", "张总", "ENTREPRENEUR", "VERIFIED", "MEDIUM"),
                Map.of("birth_year", 1980, "native_place", "上海", "source_id", 101L),
                List.of(Map.of("organization_name", "海川投资", "position_title", "董事", "source_id", 102L)),
                List.of(),
                List.of(Map.of("fact_category", "ASSET", "amount", 1000000, "source_id", 103L,
                        "description", "银行卡尾号 1234")),
                List.of(),
                List.of(),
                List.of(),
                List.of(Map.of("note_text", "手机号 13800138000", "note_type", "PREFERENCE", "source_id", 104L)),
                List.of(Map.of("enterprise_name", "星海集团", "stock_code", "600001", "relation_type", "CONTROLLER",
                        "industry_name", "制造业", "source_id", 105L, "raw_text", "原始关系描述")),
                List.of(Map.of("business_line", "工业自动化", "source_id", 105L)),
                List.of(),
                List.of(),
                List.of(),
                List.of(Map.of("member_name", "李四", "public_disclosure_level", "RESTRICTED", "source_id", 106L)),
                List.of(Map.of("relation_type", "SPOUSE", "source_id", 106L)),
                List.of(),
                List.of(Map.of("organization_name", "某商会", "organization_type", "CHAMBER", "source_id", 107L)),
                List.of(),
                List.of(),
                List.of());

        KycMaskedInput input = maskingService.mask(data);
        String payload = objectMapper.writeValueAsString(input.payload());

        assertThat(payload).contains("\"sourceRef\":\"SRC-1\"");
        assertThat(input.evidenceReferences()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "SRC-1", 101L, "SRC-2", 102L, "SRC-3", 103L, "SRC-4", 104L,
                "SRC-5", 105L, "SRC-6", 106L, "SRC-7", 107L));
        assertThat(input.sha256()).matches("[0-9a-f]{64}");
        assertThat(payload).doesNotContain(
                "张三", "张总", "上海", "海川投资", "星海集团", "600001", "李四", "某商会",
                "13800138000", "原始关系描述", "银行卡尾号");
        assertThat(payload).contains("ENTREPRENEUR", "制造业", "工业自动化", "CONTROLLER");
    }
}
