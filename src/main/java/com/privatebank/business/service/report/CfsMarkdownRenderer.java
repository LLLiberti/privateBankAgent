package com.privatebank.business.service.report;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
public class CfsMarkdownRenderer {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX");
    private static final List<String> ATTACHMENT_TITLES = List.of(
            "实控人及其他关键人物详情",
            "公司大事记及财务分析",
            "公司主要产品及服务介绍",
            "行业知识及竞争对手情况",
            "公司及个人舆情",
            "工作优势及营销话术");

    public byte[] render(CfsReportDocument report) {
        StringBuilder md = new StringBuilder(8192);
        md.append("# 客户综合金融服务方案（CFS）\n\n");
        md.append("> 本报告已经合规检查通过，由系统根据已确认的 CFS 分析结果自动生成。\n\n");
        metadata(md, "客户", report.customerId());
        metadata(md, "CFS 版本", String.valueOf(report.cfsVersion()));
        metadata(md, "生成时间", TIME_FORMAT.format(report.generatedAt()));
        md.append('\n');

        section(md, "第一章 客户信息", report.chapter1CustomerInfo());
        section(md, "第二章 服务方案", report.chapter2ServicePlan());
        section(md, "第三章 营销策略", report.chapter3MarketingStrategy());

        for (int index = 0; index < ATTACHMENT_TITLES.size(); index++) {
            String body = index < report.attachments().size() ? report.attachments().get(index) : "未提供。";
            section(md, "附件" + (index + 1) + " " + ATTACHMENT_TITLES.get(index), body);
        }

        md.append("---\n\n");
        md.append("## 数据来源\n\n");
        dataSources(md, report.dataSources());
        md.append("---\n\n");
        md.append("本报告仅供获授权的客户经理及内部审核人员使用。涉及估算、待核实或声誉风险的信息，须在对客使用前完成必要的人工复核。\n");
        return md.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void metadata(StringBuilder md, String label, String value) {
        md.append("- **").append(label).append("：** ")
                .append(StringUtils.hasText(value) ? escapeInline(value) : "未提供").append('\n');
    }

    private void section(StringBuilder md, String title, String body) {
        md.append("## ").append(title).append("\n\n");
        md.append(StringUtils.hasText(body) ? body : "未提供。").append("\n\n");
    }

    private void dataSources(StringBuilder md, List<CfsReportDocument.DataSourceItem> sources) {
        if (sources.isEmpty()) {
            md.append("暂无可展示的数据来源，需人工补充。\n\n");
            return;
        }
        for (int index = 0; index < sources.size(); index++) {
            CfsReportDocument.DataSourceItem source = sources.get(index);
            md.append("### 来源").append(index + 1).append("｜")
                    .append(source.sourceType()).append("\n\n");
            metadata(md, "来源名称", source.sourceName());
            metadata(md, "定位信息", source.locator());
            if (StringUtils.hasText(source.sourceDate())) {
                metadata(md, "来源日期", source.sourceDate());
            }
            metadata(md, "支持内容", source.summary());
            metadata(md, "来源级别", source.sourceLevel());
            md.append('\n');
        }
    }

    private String escapeInline(String value) {
        return value.replace("\\", "\\\\").replace("*", "\\*");
    }
}
