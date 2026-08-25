package com.privatebank.business.service.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.business.dto.customer.EvidenceResponse;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CfsReportRenderersTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final CfsReportDocument report = report();

    @Test
    void rendersStructuredMarkdown() {
        String markdown = new String(new CfsMarkdownRenderer().render(report), StandardCharsets.UTF_8);

        assertThat(markdown)
                .contains("# 客户综合金融服务方案（CFS）")
                .contains("## 第一章 客户信息")
                .contains("## 第三章 营销策略")
                .contains("## 附件1 实控人及其他关键人物详情")
                .contains("## 附件6 工作优势及营销话术")
                .contains("## 数据来源")
                .contains("中风险", "待核实")
                .doesNotContain("核心营销策略摘要", "专项附件", "`SRC-1`", "ART-KYC")
                .doesNotContain("MEDIUM", "PENDING_VERIFICATION");
    }

    @Test
    void rendersReadableWordDocument() throws Exception {
        byte[] bytes = new CfsDocxRenderer().render(report);

        assertThat(bytes).isNotEmpty();
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            String text = document.getParagraphs().stream()
                    .map(XWPFParagraph::getText)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertThat(text)
                    .contains("第一章 客户信息")
                    .contains("第三章 营销策略")
                    .contains("附件1 实控人及其他关键人物详情")
                    .contains("附件6 工作优势及营销话术")
                    .contains("数据来源")
                    .doesNotContain("专项附件", "核心营销策略摘要");
        }
    }

    @Test
    void rendersSearchableChinesePdfWhenChineseFontIsAvailable() throws Exception {
        Path font = chineseFont();
        Assumptions.assumeTrue(font != null, "No Chinese TTF font available on this build host");

        byte[] bytes = new CfsPdfRenderer(font.toString()).render(report);

        assertThat(bytes).isNotEmpty();
        try (PDDocument document = Loader.loadPDF(bytes)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(document.getNumberOfPages()).isGreaterThanOrEqualTo(2);
            assertThat(text).contains("第一章 客户信息")
                    .contains("附件6 工作优势及营销话术")
                    .contains("数据来源")
                    .doesNotContain("核心营销策略摘要");
        }
    }

    @Test
    void restoresAliasesAndBuildsAtMostTenConcreteDataSources() throws Exception {
        StringBuilder productEvidence = new StringBuilder();
        for (int index = 1; index <= 10; index++) {
            if (!productEvidence.isEmpty()) {
                productEvidence.append(',');
            }
            productEvidence.append("{\"content\":\"产品资料")
                    .append(index).append("\",\"score\":0.9}");
        }
        var root = objectMapper.readTree("""
                {
                  "customerId":"P-1",
                  "cfsVersion":1,
                  "cfsStructure":{
                    "chapter1CustomerInfo":"P-1为客户本人，PERSON维度信息完整。",
                    "chapter2ServicePlan":"服务方案。",
                    "chapter3MarketingStrategy":"营销策略。",
                    "attachments":["附件1","附件2","附件3","附件4","附件5","附件6"]
                  },
                  "marketingStrategy":"摘要",
                  "communicationGuide":"话术",
                  "comprehensiveRiskAssessment":"MEDIUM",
                  "pendingVerificationItems":[],
                  "estimatedDataItems":[],
                  "sourceRefs":["SRC-1"],
                  "productEvidenceRefs":[%s],
                  "ruleRefs":["RULE-1"],
                  "inputArtifactRefs":{"kyc":"ART-KYC","market":"ART-MARKET","kyp":"ART-KYP"}
                }
                """.formatted(productEvidence));
        var kyc = objectMapper.readTree("""
                {
                  "aliasMappings":{"P-1":"张三"},
                  "evidenceReferences":{"SRC-1":42}
                }
                """);
        CfsReportDocument document = new CfsReportDocumentFactory().create(
                root, kyc, sourceId -> new EvidenceResponse(
                        sourceId, "客户资料.xlsx", "客户信息", 8, "职业经历", "D8",
                        "张三创办企业", "一级来源", LocalDate.of(2026, 8, 1), "客户档案"),
                "WF-1", "ART-CFS", "ART-COMPLIANCE",
                OffsetDateTime.parse("2026-08-21T10:15:30+08:00"));

        assertThat(document.customerId()).isEqualTo("张三");
        assertThat(document.chapter1CustomerInfo()).contains("张三", "个人维度").doesNotContain("P-1", "PERSON");
        assertThat(document.dataSources()).hasSize(10);
        assertThat(document.dataSources().getFirst())
                .extracting(CfsReportDocument.DataSourceItem::sourceName,
                        CfsReportDocument.DataSourceItem::locator,
                        CfsReportDocument.DataSourceItem::summary)
                .containsExactly("客户资料.xlsx", "客户信息 / 第8行 / 职业经历 / D8 / 客户档案", "张三创办企业");
        assertThat(document.dataSources()).allSatisfy(source ->
                assertThat(source.toString()).doesNotContain("SRC-", "ART-", "RULE-"));
    }

    private CfsReportDocument report() {
        try {
            return new CfsReportDocumentFactory().create(objectMapper.readTree("""
                    {
                      "customerId":"P-1",
                      "cfsVersion":1,
                      "cfsStructure":{
                        "chapter1CustomerInfo":"客户与企业概况。",
                        "chapter2ServicePlan":"综合服务建议。",
                        "chapter3MarketingStrategy":"营销接触方案。",
                        "attachments":["家庭结构说明","企业大事记","主要产品","行业趋势","舆情信息","本行优势"]
                      },
                      "marketingStrategy":"核心营销策略。",
                      "communicationGuide":"尊重专业、价值前置。",
                      "comprehensiveRiskAssessment":"整体风险为MEDIUM，事项处于PENDING_VERIFICATION状态。",
                      "pendingVerificationItems":["核实资产估值依据"],
                      "estimatedDataItems":["部分资产数据为估算值"],
                      "sourceRefs":["SRC-1","SRC-2"],
                      "productEvidenceRefs":[],
                      "ruleRefs":["RULE-1"],
                      "inputArtifactRefs":{"kyc":"ART-KYC","market":"ART-MARKET","kyp":"ART-KYP"}
                    }
                    """), "WF-1", "ART-CFS", "ART-COMPLIANCE",
                    OffsetDateTime.parse("2026-08-21T10:15:30+08:00"));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Path chineseFont() {
        String windows = System.getenv("WINDIR");
        List<Path> candidates = new java.util.ArrayList<>();
        if (windows != null) {
            candidates.add(Path.of(windows, "Fonts", "simhei.ttf"));
            candidates.add(Path.of(windows, "Fonts", "SimsunExtG.ttf"));
        }
        candidates.add(Path.of("/usr/share/fonts/truetype/droid/DroidSansFallbackFull.ttf"));
        candidates.add(Path.of("/mnt/c/Windows/Fonts/simhei.ttf"));
        return candidates.stream().filter(Files::isRegularFile).findFirst().orElse(null);
    }
}
