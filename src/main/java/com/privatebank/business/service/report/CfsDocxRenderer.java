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

@Component
public class CfsDocxRenderer {

    private static final String LATIN_FONT = "Calibri";
    private static final String EAST_ASIA_FONT = "Microsoft YaHei";
    private static final String NAVY = "203748";
    private static final String BLUE = "2E5D7B";
    private static final String GRAY = "666666";
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX");
    private static final List<String> ATTACHMENT_TITLES = List.of(
            "实控人及其他关键人物详情", "公司大事记及财务分析", "公司主要产品及服务介绍",
            "行业知识及竞争对手情况", "公司及个人舆情", "工作优势及营销话术");

    public byte[] render(CfsReportDocument report) {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            configurePage(document);
            addHeaderAndFooter(document);
            BigInteger bulletNumberingId = createBulletNumbering(document);
            addCover(document, report);
            document.createParagraph().createRun().addBreak(BreakType.PAGE);

            addSection(document, "第一章 客户信息", report.chapter1CustomerInfo(), bulletNumberingId);
            addSection(document, "第二章 服务方案", report.chapter2ServicePlan(), bulletNumberingId);
            addSection(document, "第三章 营销策略", report.chapter3MarketingStrategy(), bulletNumberingId);

            for (int index = 0; index < ATTACHMENT_TITLES.size(); index++) {
                addHeading(document, "附件" + (index + 1) + " " + ATTACHMENT_TITLES.get(index), 1);
                String body = index < report.attachments().size()
                        ? report.attachments().get(index) : "未提供。";
                addStructuredBody(document, body, bulletNumberingId);
            }

            document.createParagraph().createRun().addBreak(BreakType.PAGE);
            addDataSources(document, report.dataSources(), bulletNumberingId);
            addDisclaimer(document);

            document.getProperties().getCoreProperties().setTitle("客户综合金融服务方案（CFS）");
            document.getProperties().getCoreProperties().setCreator("私人银行智能助手");
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
        footerRun.setText("私人银行客户综合金融服务方案");
    }

    private void addCover(XWPFDocument document, CfsReportDocument report) {
        addSpacer(document, 84);
        XWPFParagraph kicker = document.createParagraph();
        kicker.setAlignment(ParagraphAlignment.CENTER);
        kicker.setSpacingAfter(240);
        XWPFRun kickerRun = kicker.createRun();
        styleRun(kickerRun, 11, BLUE, true);
        kickerRun.setText("私人银行客户综合金融服务方案");

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

    private void addSection(
            XWPFDocument document, String title, String body, BigInteger numberingId) {
        addHeading(document, title, 1);
        addStructuredBody(document, value(body), numberingId);
    }

    private void addStructuredBody(
            XWPFDocument document, String text, BigInteger numberingId) {
        for (String rawLine : value(text).split("\\n")) {
            String line = rawLine.trim();
            if (!StringUtils.hasText(line)) {
                continue;
            }
            if (line.startsWith("### ")) {
                addHeading(document, line.substring(4).trim(), 2);
            } else if (line.startsWith("## ")) {
                addHeading(document, line.substring(3).trim(), 2);
            } else if (line.startsWith("- ")) {
                addBullet(document, line.substring(2).trim(), numberingId);
            } else {
                addBody(document, line);
            }
        }
    }

    private void addDataSources(
            XWPFDocument document,
            List<CfsReportDocument.DataSourceItem> sources,
            BigInteger numberingId) {
        addHeading(document, "数据来源", 1);
        if (sources.isEmpty()) {
            addBody(document, "暂无可展示的数据来源，需人工补充。");
            return;
        }
        for (int index = 0; index < sources.size(); index++) {
            CfsReportDocument.DataSourceItem source = sources.get(index);
            addHeading(document, "来源" + (index + 1) + "｜" + source.sourceType(), 2);
            addBullet(document, "来源名称：" + source.sourceName(), numberingId);
            addBullet(document, "定位信息：" + source.locator(), numberingId);
            if (StringUtils.hasText(source.sourceDate())) {
                addBullet(document, "来源日期：" + source.sourceDate(), numberingId);
            }
            addBullet(document, "支持内容：" + source.summary(), numberingId);
            addBullet(document, "来源级别：" + source.sourceLevel(), numberingId);
        }
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
        level.addNewLvlText().setVal("•");
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
