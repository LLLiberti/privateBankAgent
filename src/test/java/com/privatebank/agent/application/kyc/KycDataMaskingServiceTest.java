package com.privatebank.agent.application.kyc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.agent.domain.kyc.KycCustomerData;
import com.privatebank.agent.domain.kyc.KycInputValidationException;
import com.privatebank.agent.domain.kyc.KycMaskedInput;
import com.privatebank.agent.domain.kyc.KycGraphRelationship;
import com.privatebank.business.dto.customer.CustomerSummaryResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KycDataMaskingServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final KycDataMaskingService maskingService = new KycDataMaskingService(objectMapper);

    @Test
    void createsMaskingServiceThroughSpringWhenMultipleConstructorsExist() {
        new ApplicationContextRunner()
                .withBean(ObjectMapper.class, () -> objectMapper)
                .withUserConfiguration(KycDataMaskingService.class)
                .run(context -> assertThat(context).hasSingleBean(KycDataMaskingService.class));
    }

    @Test
    void projectsFourDimensionsIntoAliasesAndControlledSemanticCodes() throws Exception {
        KycCustomerData data = sampleData();

        KycMaskedInput input = maskingService.mask(data);
        String payload = objectMapper.writeValueAsString(input.payload());

        assertThat(input.payload()).containsEntry("contractVersion", "kyc-input.v4");
        assertThat(input.sha256()).matches("[0-9a-f]{64}");
        assertThat(input.evidenceReferences()).containsKeys("SRC-1", "SRC-2", "SRC-3", "SRC-4", "SRC-5");

        Map<String, Object> enterprise = section(input, "enterprise");
        assertThat(records(enterprise, "relations").getFirst()).containsEntry("enterpriseAlias", "E-1");
        assertThat(records(enterprise, "businesses").getFirst())
                .containsEntry("enterpriseAlias", "E-1")
                .containsEntry("businessCategories", List.of("CLOUD_COMPUTING", "ARTIFICIAL_INTELLIGENCE"));
        assertThat(records(enterprise, "events").getFirst())
                .containsEntry("enterpriseAlias", "E-1")
                .containsEntry("eventSignals", List.of("SHARE_REPURCHASE", "AI_STRATEGY"));
        assertThat(records(enterprise, "marketRelations").getFirst())
                .containsEntry("enterpriseAlias", "E-1")
                .containsEntry("counterpartyAlias", "C-1");

        Map<String, Object> person = section(input, "person");
        assertThat(records(person, "interactionSignals").getFirst())
                .containsEntry("personAlias", "P-1")
                .containsEntry("topicCodes", List.of("LONG_TERM_PLANNING", "DIGITAL_TECHNOLOGY", "PHILANTHROPY"));

        Map<String, Object> family = section(input, "family");
        assertThat(records(family, "members").getFirst()).containsEntry("familyAlias", "F-1");
        assertThat(records(family, "relations").getFirst()).containsEntry("familyAlias", "F-1");

        Map<String, Object> social = section(input, "social");
        assertThat(records(social, "relations").getFirst()).containsEntry("organizationAlias", "O-2");
        assertThat(records(social, "activities").getFirst())
                .containsEntry("activitySignals", List.of("PHILANTHROPY", "EDUCATION_SUPPORT"));
        assertThat(records(social, "reputationRisks").getFirst())
                .containsEntry("riskCategories", List.of("ANTITRUST", "DATA_SECURITY"));

        assertThat(payload).doesNotContain(
                "马化腾", "腾讯", "某慈善基金会", "某竞争企业", "张三", "13800138000");
        assertThat(payload).contains(
                "长期关注人工智能和公益", "云与人工智能业务", "实施股权回购并加大人工智能投入",
                "涉及反垄断和数据安全的监管关注", "原始备注");
        assertThat(payload).doesNotContain(
                "fullName", "enterpriseName", "organizationName",
                "birth_date", "native_place", "birth_place", "residence", "school_name",
                "1971-10-29", "深圳", "广东", "某大学");
    }

    @Test
    void projectsNeo4jRelationshipsWithoutLeakingGraphIdentifiers() throws Exception {
        KycCustomerData base = sampleData();
        KycCustomerData data = new KycCustomerData(
                base.summary(), base.profile(), base.careers(), base.riskPreferences(), base.financialFacts(),
                base.holdings(), base.financialEvents(), base.serviceRecords(), base.interactionNotes(),
                base.enterpriseRelations(), base.enterpriseBusinesses(), base.enterpriseFinancialMetrics(),
                base.enterpriseEvents(), base.enterpriseMarketRelations(), base.familyMembers(),
                base.familyRelations(), base.successionArrangements(), base.socialRelations(),
                base.socialActivities(), base.publicReputations(), base.reputationRisks(),
                List.of(
                        new KycGraphRelationship("PERSON:1", "PERSON", true, "CONTROLS",
                                "ENTERPRISE:501", "ENTERPRISE", false, 501L, "VERIFIED", 0.99, 1),
                        new KycGraphRelationship("ENTERPRISE:501", "ENTERPRISE", false, "HAS_EVENT",
                                "EVENT:9001", "EVENT", false, 502L, "VERIFIED", 0.90, 2)));

        KycMaskedInput input = maskingService.mask(data);
        @SuppressWarnings("unchecked")
        Map<String, Object> graphProjection = (Map<String, Object>) input.payload().get("relationshipGraph");
        List<Map<String, Object>> graph = (List<Map<String, Object>>) graphProjection.get("relationships");
        String payload = objectMapper.writeValueAsString(input.payload());

        assertThat(input.payload()).containsEntry("contractVersion", "kyc-input.v4");
        assertThat(graphProjection)
                .containsEntry("available", true)
                .containsEntry("relationshipCount", 2)
                .containsKey("evidenceRefs");
        assertThat(graph).hasSize(2);
        assertThat(graph.get(0))
                .containsEntry("startAlias", "P-1")
                .containsEntry("relationType", "CONTROLS")
                .containsEntry("endAlias", "E-1");
        assertThat(graph.get(1))
                .containsEntry("startAlias", "E-1")
                .containsEntry("endAlias", "V-1")
                .containsEntry("distance", 2)
                .containsEntry("pathScope", "TWO_HOP")
                .containsEntry("evidenceOrigin", "NEO4J_RELATIONSHIP");
        assertThat(payload).doesNotContain("PERSON:1", "ENTERPRISE:501", "EVENT:9001");
        assertThat(input.evidenceReferences().get(graph.get(0).get("sourceRef"))).isEqualTo(501L);
        assertThat(input.evidenceReferences().get(graph.get(1).get("sourceRef"))).isEqualTo(502L);
    }

    @Test
    void retainsUnknownBusinessFieldsWhileRedactingIdentifiersRecursively() throws Exception {
        KycCustomerData base = sampleData();
        Map<String, Object> unknownRiskRecord = new java.util.LinkedHashMap<>();
        unknownRiskRecord.put("source_id", 777L);
        unknownRiskRecord.put("raw_text", "must never cross the model boundary");
        unknownRiskRecord.put("investment_horizon", "5年以上");
        unknownRiskRecord.put("liquidity_requirement", "中等");
        unknownRiskRecord.put("custom_scenario", Map.of(
                "comment", "马化腾计划联系13800138000，在深圳市某区长期配置人工智能主题",
                "allocation_ratio", 45,
                "raw_text", "nested raw evidence must also be removed"));
        KycCustomerData data = new KycCustomerData(
                base.summary(), base.profile(), base.careers(), List.of(unknownRiskRecord), base.financialFacts(),
                base.holdings(), base.financialEvents(), base.serviceRecords(), base.interactionNotes(),
                base.enterpriseRelations(), base.enterpriseBusinesses(), base.enterpriseFinancialMetrics(),
                base.enterpriseEvents(), base.enterpriseMarketRelations(), base.familyMembers(),
                base.familyRelations(), base.successionArrangements(), base.socialRelations(),
                base.socialActivities(), base.publicReputations(), base.reputationRisks(), base.graphRelationships());

        String payload = objectMapper.writeValueAsString(maskingService.mask(data).payload());

        assertThat(payload).doesNotContain("raw_text", "must never cross", "nested raw evidence");

        assertThat(payload).contains("5年以上", "中等", "custom_scenario", "allocation_ratio", "长期配置人工智能主题");
        assertThat(payload).contains("P-1", "[PHONE_REDACTED]", "[SENSITIVE_REDACTED]");
        assertThat(payload).doesNotContain("马化腾", "13800138000", "深圳市某区");
    }

    @Test
    void rejectsAnyAccidentalRawTextFieldAtTheModelBoundary() {
        KycInputSafetyValidator validator = new KycInputSafetyValidator();

        assertThatThrownBy(() -> validator.validate(
                Map.of("contractVersion", "kyc-input.v4", "person", Map.of("rawText", "原始备注")), Set.of()))
                .isInstanceOf(KycInputValidationException.class)
                .hasMessageContaining("禁止字段");
        for (String field : List.of(
                "birth_date", "native_place", "birth_place", "residence", "school_name")) {
            assertThatThrownBy(() -> validator.validate(
                    Map.of("contractVersion", "kyc-input.v4", "person", Map.of(field, "某值")), Set.of()))
                    .isInstanceOf(KycInputValidationException.class)
                    .hasMessageContaining("禁止字段");
        }
        assertThatCode(() -> validator.validate(
                Map.of("contractVersion", "kyc-input.v4", "person", Map.of("signal", "原始备注")), Set.of()))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validate(
                Map.of("contractVersion", "kyc-input.v4", "person", Map.of("signal", "联系13800138000")), Set.of()))
                .isInstanceOf(KycInputValidationException.class)
                .hasMessageContaining("格式化敏感信息");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> section(KycMaskedInput input, String name) {
        return (Map<String, Object>) input.payload().get(name);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> records(Map<String, Object> section, String name) {
        return (List<Map<String, Object>>) section.get(name);
    }

    private KycCustomerData sampleData() {
        return new KycCustomerData(
                new CustomerSummaryResponse(1L, "马化腾", "Pony", "ENTREPRENEUR", "VERIFIED", "MEDIUM"),
                Map.ofEntries(
                        Map.entry("birth_date", "1971-10-29"), Map.entry("birth_year", 1971),
                        Map.entry("native_place", "深圳"), Map.entry("birth_place", "广东"),
                        Map.entry("residence", "深圳市某区"), Map.entry("school_name", "某大学"),
                        Map.entry("source_id", 101L)),
                List.of(Map.ofEntries(
                        Map.entry("organization_name", "腾讯科技"), Map.entry("position_title", "董事会主席"),
                        Map.entry("start_date", "1998-11-01"), Map.entry("source_id", 102L),
                        Map.entry("verification_status", "VERIFIED"))),
                List.of(Map.ofEntries(Map.entry("risk_level", "MEDIUM"), Map.entry("max_drawdown", 0.15),
                        Map.entry("source_id", 103L), Map.entry("verification_status", "VERIFIED"))),
                List.of(Map.ofEntries(Map.entry("fact_category", "ASSET"), Map.entry("asset_type", "股权投资"),
                        Map.entry("amount", 1000000), Map.entry("currency_code", "CNY"), Map.entry("source_id", 104L))),
                List.of(Map.ofEntries(Map.entry("product_type", "私募基金"), Map.entry("amount", 200000),
                        Map.entry("currency_code", "CNY"), Map.entry("source_id", 105L))),
                List.of(),
                List.of(),
                List.of(Map.ofEntries(Map.entry("note_type", "PREFERENCE"),
                        Map.entry("note_text", "长期关注人工智能和公益，原始备注"),
                        Map.entry("is_explicit_expression", true), Map.entry("source_id", 106L))),
                List.of(Map.ofEntries(Map.entry("enterprise_id", 501L), Map.entry("enterprise_name", "腾讯科技"),
                        Map.entry("title", "董事会主席"), Map.entry("relation_type", "CONTROLLER"),
                        Map.entry("industry_name", "互联网科技"), Map.entry("headquarters", "深圳市"),
                        Map.entry("source_id", 201L))),
                List.of(Map.ofEntries(Map.entry("enterprise_id", 501L), Map.entry("business_line", "云与人工智能业务"),
                        Map.entry("business_description", "面向企业提供云计算和人工智能服务"), Map.entry("source_id", 202L))),
                List.of(Map.ofEntries(Map.entry("enterprise_id", 501L), Map.entry("reporting_period", "2025"),
                        Map.entry("metric_name", "REVENUE"), Map.entry("metric_value", 1000),
                        Map.entry("unit_name", "CNY_100M"), Map.entry("source_id", 203L))),
                List.of(Map.ofEntries(Map.entry("enterprise_id", 501L), Map.entry("event_type", "CORPORATE_ACTION"),
                        Map.entry("event_description", "实施股权回购并加大人工智能投入"), Map.entry("risk_level", "MEDIUM"),
                        Map.entry("source_id", 204L))),
                List.of(Map.ofEntries(Map.entry("enterprise_id", 501L), Map.entry("counterpart_name", "某竞争企业"),
                        Map.entry("relation_type", "COMPETITOR"), Map.entry("source_id", 205L))),
                List.of(Map.ofEntries(Map.entry("family_member_id", 301L), Map.entry("member_name", "张三"),
                        Map.entry("public_disclosure_level", "RESTRICTED"), Map.entry("source_id", 301L))),
                List.of(Map.ofEntries(Map.entry("family_member_id", 301L), Map.entry("relation_type", "SPOUSE"),
                        Map.entry("public_disclosure_level", "RESTRICTED"), Map.entry("source_id", 302L))),
                List.of(Map.ofEntries(Map.entry("enterprise_id", 501L), Map.entry("arrangement_status", "DRAFT"),
                        Map.entry("governance_model", "家族信托与董事会治理"), Map.entry("source_id", 303L))),
                List.of(Map.ofEntries(Map.entry("social_organization_id", 601L), Map.entry("organization_name", "某慈善基金会"),
                        Map.entry("organization_type", "FOUNDATION"), Map.entry("relation_type", "BOARD_MEMBER"),
                        Map.entry("role_title", "理事"), Map.entry("source_id", 401L))),
                List.of(Map.ofEntries(Map.entry("activity_type", "DONATION"), Map.entry("activity_description", "支持教育公益项目"),
                        Map.entry("amount", 10), Map.entry("currency_code", "CNY"), Map.entry("source_id", 402L))),
                List.of(Map.ofEntries(Map.entry("reputation_type", "MEDIA"), Map.entry("title", "全球科技企业家"),
                        Map.entry("description", "具有全球影响力"), Map.entry("source_id", 403L))),
                List.of(Map.ofEntries(Map.entry("risk_topic", "反垄断与数据安全"),
                        Map.entry("risk_description", "涉及反垄断和数据安全的监管关注"),
                        Map.entry("risk_level", "HIGH"), Map.entry("source_id", 404L))));
    }
}
