package com.privatebank.business.service.report;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.BreakIterator;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class CfsPdfRenderer {

    private static final PDRectangle PAGE_SIZE = PDRectangle.LETTER;
    private static final Color NAVY = new Color(32, 55, 72);
    private static final Color BLUE = new Color(46, 93, 123);
    private static final Color BODY = new Color(34, 34, 34);
    private static final Color GRAY = new Color(102, 102, 102);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX");
    private static final List<String> ATTACHMENT_TITLES = List.of(
            "实控人及其他关键人物详情", "公司大事记及财务分析", "公司主要产品及服务介绍",
            "行业知识及竞争对手情况", "公司及个人舆情", "工作优势及营销话术");

    private final String configuredFontPath;

    public CfsPdfRenderer(@Value("${private-bank.report.pdf-font-path:}") String configuredFontPath) {
        this.configuredFontPath = configuredFontPath;
    }

    public byte[] render(CfsReportDocument report) {
        List<Path> fontPaths = resolveFontPaths();
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PdfFontFamily font = loadFonts(document, fontPaths);
            requireReportGlyphs(font);
            try (PdfCanvas canvas = new PdfCanvas(document, font)) {
                addCover(canvas, report);
                canvas.pageBreak();
                addSection(canvas, "第一章 客户信息", report.chapter1CustomerInfo());
                addSection(canvas, "第二章 服务方案", report.chapter2ServicePlan());
                addSection(canvas, "第三章 营销策略", report.chapter3MarketingStrategy());

                for (int index = 0; index < ATTACHMENT_TITLES.size(); index++) {
                    canvas.heading("附件" + (index + 1) + " " + ATTACHMENT_TITLES.get(index), 16, BLUE);
                    String body = index < report.attachments().size()
                            ? report.attachments().get(index) : "未提供。";
                    addStructuredBody(canvas, body);
                }

                canvas.pageBreak();
                addDataSources(canvas, report.dataSources());
                canvas.spacer(8);
                canvas.paragraph(
                        "重要提示：涉及估算、待核实或声誉风险的信息，须在对客使用前完成必要的人工复核。",
                        9, GRAY, 0, 0);
            }
            addPageNumbers(document, font);
            document.getDocumentInformation().setTitle("客户综合金融服务方案（CFS）");
            document.getDocumentInformation().setAuthor("私人银行智能助手");
            document.save(output);
            return output.toByteArray();
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "PDF报告生成失败，请确认字体支持报告中的全部中英文字符：" + fontPaths, exception);
        }
    }

    private void addCover(PdfCanvas canvas, CfsReportDocument report) throws IOException {
        canvas.spacer(88);
        canvas.centered("私人银行客户综合金融服务方案", 10, BLUE, 26);
        canvas.centered("客户综合金融服务方案", 28, NAVY, 12);
        canvas.centered("CFS 3+6 专业分析报告", 14, BLUE, 48);
        canvas.centered("客户标识：" + value(report.customerId()), 11, BODY, 10);
        canvas.centered("CFS 版本：V" + report.cfsVersion(), 11, BODY, 10);
        canvas.centered("审核状态：人工审核通过", 11, BODY, 10);
        canvas.centered("生成时间：" + TIME_FORMAT.format(report.generatedAt()), 11, BODY, 10);
        canvas.centered(
                "本报告由系统根据已通过合规检查的 CFS 分析结果自动生成，仅供内部授权人员使用。",
                9, GRAY, 0);
    }

    private void addSection(PdfCanvas canvas, String title, String body) throws IOException {
        canvas.heading(title, 16, BLUE);
        addStructuredBody(canvas, value(body));
    }

    private void addStructuredBody(PdfCanvas canvas, String text) throws IOException {
        for (String rawLine : value(text).split("\\n")) {
            String line = rawLine.trim();
            if (!StringUtils.hasText(line)) {
                continue;
            }
            if (line.startsWith("### ")) {
                canvas.heading(line.substring(4).trim(), 13, NAVY);
            } else if (line.startsWith("## ")) {
                canvas.heading(line.substring(3).trim(), 13, NAVY);
            } else if (line.startsWith("- ")) {
                canvas.bullet(line.substring(2).trim());
            } else {
                canvas.paragraph(line, 11, BODY, 18, 12);
            }
        }
    }

    private void addDataSources(
            PdfCanvas canvas, List<CfsReportDocument.DataSourceItem> sources) throws IOException {
        canvas.heading("数据来源", 16, BLUE);
        if (sources.isEmpty()) {
            canvas.paragraph("暂无可展示的数据来源，需人工补充。", 11, BODY, 0, 12);
            return;
        }
        for (int index = 0; index < sources.size(); index++) {
            CfsReportDocument.DataSourceItem source = sources.get(index);
            canvas.heading("来源" + (index + 1) + "｜" + source.sourceType(), 13, NAVY);
            canvas.bullet("来源名称：" + source.sourceName());
            canvas.bullet("定位信息：" + source.locator());
            if (StringUtils.hasText(source.sourceDate())) {
                canvas.bullet("来源日期：" + source.sourceDate());
            }
            canvas.bullet("支持内容：" + source.summary());
            canvas.bullet("来源级别：" + source.sourceLevel());
        }
    }

    private void addPageNumbers(PDDocument document, PdfFontFamily font) throws IOException {
        int total = document.getNumberOfPages();
        for (int index = 0; index < total; index++) {
            PDPage page = document.getPage(index);
            try (PDPageContentStream content = new PDPageContentStream(
                    document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                String footer = "私人银行客户综合金融服务方案    " + (index + 1) + " / " + total;
                float width = textWidth(font, footer, 8);
                font.writeText(content, footer, (PAGE_SIZE.getWidth() - width) / 2, 30, 8, GRAY);
            }
        }
    }

    private List<Path> resolveFontPaths() {
        Set<Path> candidates = new LinkedHashSet<>();
        if (StringUtils.hasText(configuredFontPath)) {
            Path configured = Path.of(configuredFontPath).toAbsolutePath().normalize();
            if (!Files.isRegularFile(configured)) {
                throw new IllegalStateException("配置的PDF字体不存在：" + configured);
            }
            candidates.add(configured);
        }
        String windows = System.getenv("WINDIR");
        if (StringUtils.hasText(windows)) {
            candidates.add(Path.of(windows, "Fonts", "simhei.ttf"));
            candidates.add(Path.of(windows, "Fonts", "SimsunExtG.ttf"));
            candidates.add(Path.of(windows, "Fonts", "arial.ttf"));
        }
        candidates.add(Path.of("/usr/share/fonts/truetype/droid/DroidSansFallbackFull.ttf"));
        candidates.add(Path.of("/mnt/c/Windows/Fonts/simhei.ttf"));
        candidates.add(Path.of("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"));
        List<Path> paths = candidates.stream().filter(Files::isRegularFile).toList();
        if (paths.isEmpty()) {
            throw new IllegalStateException(
                    "未找到可用PDF字体，请安装同时覆盖中英文的TTF字体或配置PRIVATE_BANK_PDF_FONT_PATH");
        }
        return paths;
    }

    private PdfFontFamily loadFonts(PDDocument document, List<Path> fontPaths) throws IOException {
        List<PDType0Font> fonts = new ArrayList<>();
        for (Path fontPath : fontPaths) {
            fonts.add(PDType0Font.load(document, fontPath.toFile()));
        }
        return new PdfFontFamily(List.copyOf(fonts));
    }

    private void requireReportGlyphs(PdfFontFamily font) throws IOException {
        font.textWidth("C中", 1);
    }


    private String value(String text) {
        return StringUtils.hasText(text) ? text : "未提供。";
    }

    private static float textWidth(PdfFontFamily font, String text, float fontSize) throws IOException {
        return font.textWidth(text, fontSize);
    }


    private static final class PdfFontFamily {
        private final List<PDType0Font> fonts;

        private PdfFontFamily(List<PDType0Font> fonts) {
            this.fonts = fonts;
        }

        private float textWidth(String text, float fontSize) throws IOException {
            float width = 0;
            for (int codePoint : text.codePoints().toArray()) {
                String character = new String(Character.toChars(codePoint));
                PDFont font = fontFor(codePoint, character);
                width += font.getStringWidth(character) / 1000f * fontSize;
            }
            return width;
        }

        private void writeText(PDPageContentStream content, String text, float x, float y, float size, Color color)
                throws IOException {
            content.beginText();
            content.setNonStrokingColor(color);
            content.newLineAtOffset(x, y);
            PDFont active = null;
            StringBuilder run = new StringBuilder();
            for (int codePoint : text.codePoints().toArray()) {
                String character = new String(Character.toChars(codePoint));
                PDFont selected = fontFor(codePoint, character);
                if (selected != active && !run.isEmpty()) {
                    showRun(content, active, run.toString(), size);
                    run.setLength(0);
                }
                active = selected;
                run.append(character);
            }
            if (!run.isEmpty()) {
                showRun(content, active, run.toString(), size);
            }
            content.endText();
        }

        private void showRun(PDPageContentStream content, PDFont font, String text, float size) throws IOException {
            content.setFont(font, size);
            content.showText(text);
        }

        private PDFont fontFor(int codePoint, String character) throws IOException {
            for (PDType0Font font : fonts) {
                try {
                    font.getStringWidth(character);
                    return font;
                } catch (IllegalArgumentException exception) {
                    // Try the next configured fallback font.
                }
            }
            throw new IllegalArgumentException(String.format("没有字体支持字符 U+%04X", codePoint));
        }
    }

    private static final class PdfCanvas implements AutoCloseable {
        private static final float LEFT = 72;
        private static final float RIGHT = 72;
        private static final float TOP = 70;
        private static final float BOTTOM = 54;
        private static final float CONTENT_WIDTH = PAGE_SIZE.getWidth() - LEFT - RIGHT;

        private final PDDocument document;
        private final PdfFontFamily font;
        private PDPageContentStream content;
        private float y;
        private int pageNumber;

        private PdfCanvas(PDDocument document, PdfFontFamily font) throws IOException {
            this.document = document;
            this.font = font;
            newPage();
        }

        private void newPage() throws IOException {
            if (content != null) {
                content.close();
            }
            PDPage page = new PDPage(PAGE_SIZE);
            document.addPage(page);
            content = new PDPageContentStream(document, page);
            y = PAGE_SIZE.getHeight() - TOP;
            pageNumber++;
            if (pageNumber > 1) {
                writeText("客户综合金融服务方案 | 内部使用", LEFT, PAGE_SIZE.getHeight() - 38, 8, GRAY);
            }
        }

        private void pageBreak() throws IOException {
            newPage();
        }

        private void spacer(float points) throws IOException {
            ensure(points);
            y -= points;
        }

        private void centered(String text, float size, Color color, float after) throws IOException {
            List<String> lines = wrap(valueOf(text), CONTENT_WIDTH, size);
            float lineHeight = size * 1.55f;
            ensure(lines.size() * lineHeight + after);
            for (String line : lines) {
                float width = textWidth(font, line, size);
                writeText(line, Math.max(LEFT, (PAGE_SIZE.getWidth() - width) / 2), y, size, color);
                y -= lineHeight;
            }
            y -= after;
        }

        private void heading(String text, float size, Color color) throws IOException {
            float before = size >= 16 ? 18 : 12;
            float after = size >= 16 ? 10 : 7;
            List<String> lines = wrap(valueOf(text), CONTENT_WIDTH, size);
            float lineHeight = size * 1.4f;
            ensure(before + lines.size() * lineHeight + after + 28);
            y -= before;
            for (String line : lines) {
                writeText(line, LEFT, y, size, color);
                y -= lineHeight;
            }
            y -= after;
        }

        private void paragraph(
                String text, float size, Color color, float firstLineIndent, float after) throws IOException {
            String normalized = valueOf(text);
            for (String paragraph : normalized.split("\\n\\s*\\n|\\n")) {
                if (paragraph.isBlank()) {
                    continue;
                }
                float lineHeight = size * 1.65f;
                List<String> lines = wrap(paragraph.trim(), CONTENT_WIDTH - firstLineIndent, size);
                boolean first = true;
                for (String line : lines) {
                    ensure(lineHeight);
                    writeText(line, LEFT + (first ? firstLineIndent : 0), y, size, color);
                    y -= lineHeight;
                    first = false;
                }
                y -= after;
            }
        }

        private void bullet(String text) throws IOException {
            float size = 10;
            float lineHeight = size * 1.55f;
            float indent = 18;
            List<String> lines = wrap(valueOf(text), CONTENT_WIDTH - indent, size);
            boolean first = true;
            for (String line : lines) {
                ensure(lineHeight);
                if (first) {
                    writeText("•", LEFT, y, size, BODY);
                }
                writeText(line, LEFT + indent, y, size, BODY);
                y -= lineHeight;
                first = false;
            }
            y -= 6;
        }

        private List<String> wrap(String text, float maxWidth, float size) throws IOException {
            BreakIterator iterator = BreakIterator.getLineInstance(Locale.SIMPLIFIED_CHINESE);
            iterator.setText(text);
            List<String> lines = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            int start = iterator.first();
            for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
                String segment = text.substring(start, end);
                if (textWidth(font, line + segment, size) <= maxWidth) {
                    line.append(segment);
                    continue;
                }
                if (!line.isEmpty()) {
                    lines.add(line.toString().stripTrailing());
                    line.setLength(0);
                }
                appendLongSegment(lines, line, segment.stripLeading(), maxWidth, size);
            }
            if (!line.isEmpty()) {
                lines.add(line.toString().stripTrailing());
            }
            return lines.isEmpty() ? List.of("") : lines;
        }

        private void appendLongSegment(
                List<String> lines, StringBuilder line, String segment, float maxWidth, float size)
                throws IOException {
            for (int offset = 0; offset < segment.length();) {
                int codePoint = segment.codePointAt(offset);
                String character = new String(Character.toChars(codePoint));
                if (!line.isEmpty() && textWidth(font, line + character, size) > maxWidth) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                line.append(character);
                offset += Character.charCount(codePoint);
            }
        }

        private void ensure(float required) throws IOException {
            if (y - required < BOTTOM) {
                newPage();
            }
        }

        private void writeText(String text, float x, float baseline, float size, Color color) throws IOException {
            font.writeText(content, text, x, baseline, size, color);
        }

        private String valueOf(String text) {
            return StringUtils.hasText(text) ? text : "未提供。";
        }

        @Override
        public void close() throws IOException {
            if (content != null) {
                content.close();
                content = null;
            }
        }
    }
}
