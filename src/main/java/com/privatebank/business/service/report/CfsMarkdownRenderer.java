package com.privatebank.business.service.report;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Component
public class CfsMarkdownRenderer {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX");
    private static final List<String> ATTACHMENT_TITLES = List.of(
            "家庭结构与关键关系",
            "企业发展与财务概况",
            "主要产品与服务",
            "行业趋势与竞争格局",
            "客户与企业舆情",
            "本行服务优势与营销话术");

    public byte[] render(CfsReportDocument report) {
        StringBuilder md = new StringBuilder(8192);
        md.append("# 客户综合金融服务方案（CFS）\n\n");
        md.append("> 本报告已经合规检查通过，由系统根据已确认的 CFS 分析结果自动生成。\n\n");
        metadata(md, "客户标识", report.customerId());
        metadata(md, "CFS 版本", String.valueOf(report.cfsVersion()));
        metadata(md, "工作流", report.workflowId());
        metadata(md, "CFS Artifact", report.cfsArtifactId());
        metadata(md, "合规 Artifact", report.complianceArtifactId());
        metadata(md, "生成时间", TIME_FORMAT.format(report.generatedAt()));
        md.append('\n');

        section(md, "一、客户、企业与行业概况", report.chapter1CustomerInfo());
        section(md, "二、综合服务方案", report.chapter2ServicePlan());
        section(md, "三、营销与接触策略", report.chapter3MarketingStrategy());
        section(md, "四、核心营销策略摘要", report.marketingStrategy());
        section(md, "五、综合风险评估", report.comprehensiveRiskAssessment());
        section(md, "六、沟通指引", report.communicationGuide());

        md.append("## 七、专项附件\n\n");
        if (report.attachments().isEmpty()) {
            md.append("未提供。\n\n");
        } else {
            for (int index = 0; index < report.attachments().size(); index++) {
                String title = index < ATTACHMENT_TITLES.size()
                        ? ATTACHMENT_TITLES.get(index)
                        : "补充附件 " + (index + 1);
                md.append("### 7.").append(index + 1).append(' ').append(title).append("\n\n");
                md.append(report.attachments().get(index)).append("\n\n");
            }
        }

        listSection(md, "八、待核实事项", report.pendingVerificationItems());
        listSection(md, "九、估算数据及缺失信息说明", report.estimatedDataItems());

        md.append("## 十、证据与来源引用\n\n");
        referenceLine(md, "CFS 输入", report.inputArtifactRefs());
        list(md, "来源引用", report.sourceRefs(), true);
        list(md, "产品知识证据", report.productEvidenceRefs(), false);
        list(md, "规则引用", report.ruleRefs(), true);

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

    private void listSection(StringBuilder md, String title, List<String> items) {
        md.append("## ").append(title).append("\n\n");
        if (items.isEmpty()) {
            md.append("无。\n\n");
            return;
        }
        for (String item : items) {
            md.append("- ").append(item).append('\n');
        }
        md.append('\n');
    }

    private void referenceLine(StringBuilder md, String title, Map<String, String> values) {
        md.append("### ").append(title).append("\n\n");
        if (values.isEmpty()) {
            md.append("无。\n\n");
            return;
        }
        values.forEach((label, value) -> md.append("- **").append(label).append("：** `")
                .append(escapeCode(value)).append("`\n"));
        md.append('\n');
    }

    private void list(StringBuilder md, String title, List<String> items, boolean code) {
        md.append("### ").append(title).append("\n\n");
        if (items.isEmpty()) {
            md.append("无。\n\n");
            return;
        }
        for (String item : items) {
            md.append("- ");
            if (code) {
                md.append('`').append(escapeCode(item)).append('`');
            } else {
                md.append(item);
            }
            md.append('\n');
        }
        md.append('\n');
    }

    private String escapeInline(String value) {
        return value.replace("\\", "\\\\").replace("*", "\\*");
    }

    private String escapeCode(String value) {
        return value.replace("`", "\\`");
    }
}
