package com.privatebank.business.service.report;

import com.fasterxml.jackson.databind.ObjectMapper;
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
                .contains("## 五、综合风险评估")
                .contains("PENDING_VERIFICATION")
                .contains("### 7.1 家庭结构与关键关系")
                .contains("`SRC-1`");
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
                    .contains("客户综合金融服务方案")
                    .contains("综合风险评估")
                    .contains("待核实事项");
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
            assertThat(text).contains("客户综合金融服务方案").contains("综合风险评估");
        }
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
        return candidates.stream().filter(Files::isRegularFile).findFirst().orElse(null);
    }
}
