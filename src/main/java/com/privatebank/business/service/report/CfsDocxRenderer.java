package com.privatebank.business.service.report;

import org.apache.poi.xwpf.model.XWPFHeaderFooterPolicy;
import org.apache.poi.xwpf.usermodel.BreakType;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFAbstractNum;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFNumbering;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTAbstractNum;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTFonts;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTInd;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTLvl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPrGeneral;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STNumberFormat;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Component
public class CfsDocxRenderer {

    private static final String LATIN_FONT = "Calibri";
    private static final String EAST_ASIA_FONT = "Microsoft YaHei";
    private static final String NAVY = "203748";
    private static final String BLUE = "2E5D7B";
    private static final String GRAY = "666666";
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX");
    private static final List<String> ATTACHMENT_TITLES = List.of(
            "家庭结构与关键关系", "企业发展与财务概况", "主要产品与服务",
            "行业趋势与竞争格局", "客户与企业舆情", "本行服务优势与营销话术");

    public byte[] render(CfsReportDocument report) {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            configurePage(document);
            addHeaderAndFooter(document);
            BigInteger bulletNumberingId = createBulletNumbering(document);
            addCover(document, report);
            document.createParagraph().createRun().addBreak(BreakType.PAGE);

            addSection(document, "一、客户、企业与行业概况", report.chapter1CustomerInfo());
            addSection(document, "二、综合服务方案", report.chapter2ServicePlan());
            addSection(document, "三、营销与接触策略", report.chapter3MarketingStrategy());
            addSection(document, "四、核心营销策略摘要", report.marketingStrategy());
            addSection(document, "五、综合风险评估", report.comprehensiveRiskAssessment());
            addSection(document, "六、沟通指引", report.communicationGuide());

            addHeading(document, "七、专项附件", 1);
            if (report.attachments().isEmpty()) {
                addBody(document, "未提供。");
            } else {
                for (int index = 0; index < report.attachments().size(); index++) {
                    String title = index < ATTACHMENT_TITLES.size()
                            ? ATTACHMENT_TITLES.get(index)
                            : "补充附件 " + (index + 1);
                    addHeading(document, "7." + (index + 1) + " " + title, 2);
                    addBody(document, report.attachments().get(index));
                }
            }

            addListSection(document, "八、待核实事项", report.pendingVerificationItems(), bulletNumberingId);
            addListSection(document, "九、估算数据及缺失信息说明", report.estimatedDataItems(), bulletNumberingId);
            addEvidence(document, report, bulletNumberingId);
            addDisclaimer(document);

            document.getProperties().getCoreProperties().setTitle("客户综合金融服务方案（CFS）");
            document.getProperties().getCoreProperties().setCreator("Private Bank Agent");
            document.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Word报告生成失败", exception);
        }
    }

    private void configurePage(XWPFDocument document) {
        CTSectPr section = document.getDocument().getBody().isSetSectPr()
                ? document.getDocument().getBody().getSectPr()
                : document.getDocument().getBody().addNewSectPr();
        CTPageSz size = section.isSetPgSz() ? section.getPgSz() : section.addNewPgSz();
        size.setW(BigInteger.valueOf(12240));
        size.setH(BigInteger.valueOf(15840));
        CTPageMar margin = section.isSetPgMar() ? section.getPgMar() : section.addNewPgMar();
        margin.setTop(BigInteger.valueOf(1440));
        margin.setRight(BigInteger.valueOf(1440));
        margin.setBottom(BigInteger.valueOf(1440));
        margin.setLeft(BigInteger.valueOf(1440));
        margin.setHeader(BigInteger.valueOf(708));
        margin.setFooter(BigInteger.valueOf(708));
    }

    private void addHeaderAndFooter(XWPFDocument document) {
        CTSectPr section = document.getDocument().getBody().getSectPr();
        XWPFHeaderFooterPolicy policy = new XWPFHeaderFooterPolicy(document, section);
        XWPFHeader header = policy.createHeader(XWPFHeaderFooterPolicy.DEFAULT);
        XWPFParagraph headerParagraph = header.createParagraph();
        headerParagraph.setAlignment(ParagraphAlignment.RIGHT);
        XWPFRun headerRun = headerParagraph.createRun();
        styleRun(headerRun, 9, GRAY, false);
        headerRun.setText("客户综合金融服务方案 | 内部使用");

        XWPFFooter footer = policy.createFooter(XWPFHeaderFooterPolicy.DEFAULT);
        XWPFParagraph footerParagraph = footer.createParagraph();
        footerParagraph.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun footerRun = footerParagraph.createRun();
        styleRun(footerRun, 9, GRAY, false);
        footerRun.setText("Private Bank Agent | CFS Report");
    }

    private void addCover(XWPFDocument document, CfsReportDocument report) {
        addSpacer(document, 84);
        XWPFParagraph kicker = document.createParagraph();
        kicker.setAlignment(ParagraphAlignment.CENTER);
        kicker.setSpacingAfter(240);
        XWPFRun kickerRun = kicker.createRun();
        styleRun(kickerRun, 11, BLUE, true);
        kickerRun.setText("COMPREHENSIVE FINANCIAL SERVICE REPORT");

        XWPFParagraph title = document.createParagraph();
        title.setStyle("Title");
        title.setAlignment(ParagraphAlignment.CENTER);
        title.setSpacingAfter(160);
        XWPFRun titleRun = title.createRun();
        styleRun(titleRun, 30, NAVY, true);
        titleRun.setText("客户综合金融服务方案");

        XWPFParagraph subtitle = document.createParagraph();
        subtitle.setAlignment(ParagraphAlignment.CENTER);
        subtitle.setSpacingAfter(560);
        XWPFRun subtitleRun = subtitle.createRun();
        styleRun(subtitleRun, 14, BLUE, false);
        subtitleRun.setText("CFS 3+6 专业分析报告");

        addCoverMetadata(document, "客户标识", value(report.customerId()));
        addCoverMetadata(document, "CFS 版本", "V" + report.cfsVersion());
        addCoverMetadata(document, "审核状态", "人工审核通过");
        addCoverMetadata(document, "生成时间", TIME_FORMAT.format(report.generatedAt()));
        addCoverMetadata(document, "报告编号", report.cfsArtifactId());

        addSpacer(document, 360);
        XWPFParagraph notice = document.createParagraph();
        notice.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun noticeRun = notice.createRun();
        styleRun(noticeRun, 10, GRAY, false);
        noticeRun.setItalic(true);
        noticeRun.setText("本报告由系统根据已通过合规检查的 CFS 分析结果自动生成，仅供内部授权人员使用。");
    }

    private void addCoverMetadata(XWPFDocument document, String label, String value) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setAlignment(ParagraphAlignment.CENTER);
        paragraph.setSpacingAfter(80);
        XWPFRun labelRun = paragraph.createRun();
        styleRun(labelRun, 11, GRAY, true);
        labelRun.setText(label + "：");
        XWPFRun valueRun = paragraph.createRun();
        styleRun(valueRun, 11, NAVY, false);
        valueRun.setText(value);
    }

    private void addSection(XWPFDocument document, String title, String body) {
        addHeading(document, title, 1);
        addBody(document, value(body));
    }

    private void addListSection(
            XWPFDocument document, String title, List<String> items, BigInteger numberingId) {
        addHeading(document, title, 1);
        if (items.isEmpty()) {
            addBody(document, "无。");
            return;
        }
        for (String item : items) {
            addBullet(document, item, numberingId);
        }
    }

    private void addEvidence(XWPFDocument document, CfsReportDocument report, BigInteger numberingId) {
        addHeading(document, "十、证据与来源引用", 1);
        addHeading(document, "CFS 输入", 2);
        if (report.inputArtifactRefs().isEmpty()) {
            addBody(document, "无。");
        } else {
            for (Map.Entry<String, String> entry : report.inputArtifactRefs().entrySet()) {
                addBullet(document, entry.getKey() + "：" + entry.getValue(), numberingId);
            }
        }
        addReferenceList(document, "来源引用", report.sourceRefs(), numberingId);
        addReferenceList(document, "产品知识证据", report.productEvidenceRefs(), numberingId);
        addReferenceList(document, "规则引用", report.ruleRefs(), numberingId);
    }

    private void addReferenceList(
            XWPFDocument document, String title, List<String> values, BigInteger numberingId) {
        addHeading(document, title, 2);
        if (values.isEmpty()) {
            addBody(document, "无。");
            return;
        }
        values.forEach(value -> addBullet(document, value, numberingId));
    }

    private void addHeading(XWPFDocument document, String text, int level) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setStyle(level == 1 ? "Heading1" : "Heading2");
        paragraph.setSpacingBefore(level == 1 ? 360 : 240);
        paragraph.setSpacingAfter(level == 1 ? 200 : 120);
        paragraph.setKeepNext(true);
        CTPPr properties = paragraph.getCTP().isSetPPr()
                ? paragraph.getCTP().getPPr() : paragraph.getCTP().addNewPPr();
        properties.isSetOutlineLvl();
        if (!properties.isSetOutlineLvl()) {
            properties.addNewOutlineLvl();
        }
        properties.getOutlineLvl().setVal(BigInteger.valueOf(level - 1L));
        XWPFRun run = paragraph.createRun();
        styleRun(run, level == 1 ? 16 : 13, level == 1 ? BLUE : NAVY, true);
        run.setText(text);
    }

    private void addBody(XWPFDocument document, String text) {
        String[] paragraphs = value(text).split("\\n\\s*\\n|\\n");
        for (String part : paragraphs) {
            if (!StringUtils.hasText(part)) {
                continue;
            }
            XWPFParagraph paragraph = document.createParagraph();
            paragraph.setStyle("Normal");
            paragraph.setAlignment(ParagraphAlignment.BOTH);
            paragraph.setSpacingAfter(160);
            paragraph.setSpacingBetween(1.333);
            paragraph.setIndentationFirstLine(420);
            XWPFRun run = paragraph.createRun();
            styleRun(run, 11, "222222", false);
            run.setText(part.trim());
        }
    }

    private void addBullet(XWPFDocument document, String text, BigInteger numberingId) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setStyle("Normal");
        paragraph.setNumID(numberingId);
        paragraph.setSpacingAfter(80);
        paragraph.setSpacingBetween(1.208);
        XWPFRun run = paragraph.createRun();
        styleRun(run, 10, "222222", false);
        run.setText(value(text));
    }

    private BigInteger createBulletNumbering(XWPFDocument document) {
        XWPFNumbering numbering = document.createNumbering();
        CTAbstractNum abstractNum = CTAbstractNum.Factory.newInstance();
        abstractNum.setAbstractNumId(BigInteger.ZERO);
        CTLvl level = abstractNum.addNewLvl();
        level.setIlvl(BigInteger.ZERO);
        level.addNewNumFmt().setVal(STNumberFormat.BULLET);
        level.addNewLvlText().setVal("?");
        CTPPrGeneral paragraphProperties = level.addNewPPr();
        CTInd indent = paragraphProperties.addNewInd();
        indent.setLeft(BigInteger.valueOf(720));
        indent.setHanging(BigInteger.valueOf(360));
        BigInteger abstractId = numbering.addAbstractNum(new XWPFAbstractNum(abstractNum));
        return numbering.addNum(abstractId);
    }

    private void addDisclaimer(XWPFDocument document) {
        addSpacer(document, 160);
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setSpacingBefore(160);
        paragraph.setSpacingAfter(0);
        XWPFRun run = paragraph.createRun();
        styleRun(run, 9, GRAY, false);
        run.setItalic(true);
        run.setText("重要提示：涉及估算、待核实或声誉风险的信息，须在对客使用前完成必要的人工复核。");
    }

    private void addSpacer(XWPFDocument document, int afterTwips) {
        XWPFParagraph spacer = document.createParagraph();
        spacer.setSpacingAfter(afterTwips);
    }

    private void styleRun(XWPFRun run, int size, String color, boolean bold) {
        run.setFontFamily(LATIN_FONT);
        run.setFontSize(size);
        run.setColor(color);
        run.setBold(bold);
        CTRPr properties = run.getCTR().isSetRPr() ? run.getCTR().getRPr() : run.getCTR().addNewRPr();
        CTFonts fonts = properties.sizeOfRFontsArray() > 0
                ? properties.getRFontsArray(0) : properties.addNewRFonts();
        fonts.setAscii(LATIN_FONT);
        fonts.setHAnsi(LATIN_FONT);
        fonts.setEastAsia(EAST_ASIA_FONT);
    }

    private String value(String text) {
        return StringUtils.hasText(text) ? text : "未提供。";
    }
}
