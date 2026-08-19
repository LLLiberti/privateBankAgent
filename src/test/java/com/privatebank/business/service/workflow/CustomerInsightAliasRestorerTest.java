package com.privatebank.business.service.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.privatebank.business.dto.workflow.CustomerInsightAnalysisResponse.Analysis;
import com.privatebank.business.dto.workflow.CustomerInsightAnalysisResponse.Finding;
import com.privatebank.business.dto.workflow.CustomerInsightAnalysisResponse.FollowUpQuestion;
import com.privatebank.business.dto.workflow.CustomerInsightAnalysisResponse.GraphAssessment;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerInsightAliasRestorerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CustomerInsightAliasRestorer restorer = new CustomerInsightAliasRestorer();

    @Test
    void restoresOnlyCompleteAliasesInNaturalLanguageFields() {
        Analysis analysis = new Analysis(
                "P-1",
                "客户P-1控制E-1，P-10和P-1A保持原样",
                List.of(new Finding("P-1", "E-1", "P-1需要关注E-1", List.of("SRC-1", "P-1"))),
                List.of("E-1存在风险"),
                List.of("复核P-1"),
                List.of("缺少E-1材料"),
                new GraphAssessment("E-1", "图谱显示P-1关联E-1", List.of("SRC-2", "E-1")));
        var mappings = objectMapper.valueToTree(Map.of(
                "P-1", "张三",
                "E-1", "某$科技\\集团"));

        Analysis restored = restorer.restore(analysis, mappings, "ART-1");

        assertThat(restored.summary()).isEqualTo("客户张三控制某$科技\\集团，P-10和P-1A保持原样");
        assertThat(restored.findings().getFirst().finding()).isEqualTo("张三需要关注某$科技\\集团");
        assertThat(restored.riskAlerts()).containsExactly("某$科技\\集团存在风险");
        assertThat(restored.recommendedActions()).containsExactly("复核张三");
        assertThat(restored.dataGaps()).containsExactly("缺少某$科技\\集团材料");
        assertThat(restored.graphAssessment().summary()).isEqualTo("图谱显示张三关联某$科技\\集团");
        assertThat(restored.riskLevel()).isEqualTo("P-1");
        assertThat(restored.findings().getFirst().dimension()).isEqualTo("P-1");
        assertThat(restored.findings().getFirst().riskLevel()).isEqualTo("E-1");
        assertThat(restored.findings().getFirst().evidenceRefs()).containsExactly("SRC-1", "P-1");
        assertThat(restored.graphAssessment().contribution()).isEqualTo("E-1");
        assertThat(restored.graphAssessment().evidenceRefs()).containsExactly("SRC-2", "E-1");
    }

    @Test
    void restoresAliasesInFollowUpQuestions() {
        Analysis analysis = new Analysis(
                "MEDIUM",
                "客户P-1关联E-1",
                List.of(),
                List.of(),
                List.of(),
                List.of("缺少E-1材料"),
                null,
                List.of(new FollowUpQuestion("Q1", "P-1是否计划增加E-1相关配置？")));
        var mappings = objectMapper.valueToTree(Map.of(
                "P-1", "张三",
                "E-1", "某科技公司"));

        Analysis restored = restorer.restore(analysis, mappings, "ART-1");

        assertThat(restored.followUpQuestions()).containsExactly(
                new FollowUpQuestion("Q1", "张三是否计划增加某科技公司相关配置？"));
    }

    @Test
    void keepsLegacyAnalysisUnchangedWhenMappingsAreMissing() {
        Analysis analysis = analysis("客户P-1关联E-1");

        Analysis restored = restorer.restore(analysis, MissingNode.getInstance(), "ART-LEGACY");

        assertThat(restored).isSameAs(analysis);
    }

    @Test
    void ignoresMalformedEntriesAndRestoresValidMappings() {
        ObjectNode mappings = objectMapper.createObjectNode();
        mappings.put("SRC-1", "不应替换证据编号");
        mappings.put("P-1", 100);
        mappings.put("E-1", " ");
        mappings.put("O-1", "某行业协会");

        Analysis restored = restorer.restore(
                analysis("P-1与O-1存在关联，证据SRC-1"), mappings, "ART-2");

        assertThat(restored.summary()).isEqualTo("P-1与某行业协会存在关联，证据SRC-1");
    }

    @Test
    void doesNotRecursivelyRestoreAliasesIntroducedByAPlainName() {
        var mappings = objectMapper.valueToTree(Map.of(
                "P-1", "E-1的实际控制人",
                "E-1", "某科技公司"));

        Analysis restored = restorer.restore(analysis("客户P-1"), mappings, "ART-3");

        assertThat(restored.summary()).isEqualTo("客户E-1的实际控制人");
    }

    private Analysis analysis(String summary) {
        return new Analysis(
                "MEDIUM",
                summary,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null);
    }
}
