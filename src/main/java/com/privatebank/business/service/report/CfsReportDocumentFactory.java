package com.privatebank.business.service.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.privatebank.business.dto.customer.EvidenceResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CfsReportDocumentFactory {

    private static final int MAX_DATA_SOURCES = 10;
    private static final Pattern FALLBACK_ALIAS = Pattern.compile(
            "(?<![A-Za-z0-9_-])([PEFOCVMN])-([1-9][0-9]*)(?![A-Za-z0-9_-])");
    private static final Pattern RELATION_ALIAS = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_-])(SON|DAUGHTER|SPOUSE)[_-]([1-9][0-9]*)(?![A-Za-z0-9_-])");
    private static final Map<String, String> DISPLAY_TERMS = Map.ofEntries(
            Map.entry("PENDING CONFIRMATION", "待确认"),
            Map.entry("PENDING_CONFIRMATION", "待确认"),
            Map.entry("PENDING_VERIFICATION", "待核实"),
            Map.entry("REVIEW_REQUIRED", "需要复核"),
            Map.entry("UNVERIFIED", "未核实"),
            Map.entry("ENTERPRISE", "企业"),
            Map.entry("PERSON", "个人"),
            Map.entry("FAMILY", "家庭"),
            Map.entry("SOCIAL", "社会"),
            Map.entry("UNKNOWN", "未知"),
            Map.entry("MEDIUM", "中风险"),
            Map.entry("HIGH", "高风险"),
            Map.entry("LOW", "低风险"));

    public CfsReportDocument create(
            JsonNode root,
            String workflowId,
            String cfsArtifactId,
            String complianceArtifactId,
            OffsetDateTime generatedAt) {
        return create(root, MissingNode.getInstance(), ignored -> null,
                workflowId, cfsArtifactId, complianceArtifactId, generatedAt);
    }

    public CfsReportDocument create(
            JsonNode root,
            JsonNode kycResult,
            Function<Long, EvidenceResponse> evidenceResolver,
            String workflowId,
            String cfsArtifactId,
            String complianceArtifactId,
            OffsetDateTime generatedAt) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("CFS结果必须是JSON对象");
        }
        JsonNode structure = root.path("cfsStructure");
        JsonNode inputRefs = root.path("inputArtifactRefs");
        Map<String, String> inputs = new LinkedHashMap<>();
        putIfText(inputs, "KYC", inputRefs.path("kyc"));
        putIfText(inputs, "市场洞察", inputRefs.path("market"));
        putIfText(inputs, "产品专家", inputRefs.path("kyp"));
        Map<String, String> aliases = aliasMappings(kycResult.path("aliasMappings"));

        List<String> pendingItems = displayTextList(root.path("pendingVerificationItems"), aliases);
        List<String> estimatedItems = displayTextList(root.path("estimatedDataItems"), aliases);
        String riskAssessment = displayText(text(root, "comprehensiveRiskAssessment"), aliases);
        String servicePlan = appendReviewItems(
                structuredBody(displayText(text(structure, "chapter2ServicePlan"), aliases)),
                riskAssessment, pendingItems, estimatedItems);
        List<String> attachments = displayTextList(structure.path("attachments"), aliases).stream()
                .map(this::structuredBody)
                .toList();
        List<String> sourceRefs = displayTextList(root.path("sourceRefs"), Map.of());
        List<String> productEvidenceRefs = productEvidence(root.path("productEvidenceRefs"), aliases);
        List<String> ruleRefs = displayTextList(root.path("ruleRefs"), Map.of());
        List<CfsReportDocument.DataSourceItem> dataSources = dataSources(
                root, kycResult, evidenceResolver, aliases, inputs);

        return new CfsReportDocument(
                workflowId,
                cfsArtifactId,
                complianceArtifactId,
                displayText(text(root, "customerId"), aliases),
                root.path("cfsVersion").asInt(1),
                generatedAt,
                structuredBody(displayText(text(structure, "chapter1CustomerInfo"), aliases)),
                servicePlan,
                structuredBody(displayText(text(structure, "chapter3MarketingStrategy"), aliases)),
                displayText(text(root, "marketingStrategy"), aliases),
                displayText(text(root, "communicationGuide"), aliases),
                riskAssessment,
                attachments,
                pendingItems,
                estimatedItems,
                sourceRefs,
                productEvidenceRefs,
                ruleRefs,
                Map.copyOf(inputs),
                dataSources);
    }

    private String appendReviewItems(
            String servicePlan,
            String riskAssessment,
            List<String> pendingItems,
            List<String> estimatedItems) {
        List<String> reviewItems = new ArrayList<>();
        if (StringUtils.hasText(riskAssessment)) {
            reviewItems.add(riskAssessment);
        }
        pendingItems.forEach(item -> reviewItems.add("待核实：" + item));
        estimatedItems.forEach(item -> reviewItems.add("估算或缺失信息：" + item));
        if (reviewItems.isEmpty()) {
            return servicePlan;
        }
        StringBuilder result = new StringBuilder(servicePlan);
        if (!result.isEmpty()) {
            result.append("\n\n");
        }
        result.append("### 风险与待核实事项\n\n");
        reviewItems.forEach(item -> result.append("- ").append(item).append('\n'));
        return result.toString().trim();
    }

    private List<CfsReportDocument.DataSourceItem> dataSources(
            JsonNode root,
            JsonNode kycResult,
            Function<Long, EvidenceResponse> evidenceResolver,
            Map<String, String> aliases,
            Map<String, String> inputRefs) {
        LinkedHashMap<String, CfsReportDocument.DataSourceItem> unique = new LinkedHashMap<>();
        JsonNode evidenceReferences = kycResult.path("evidenceReferences");
        for (String sourceRef : rawTextList(root.path("sourceRefs"))) {
            EvidenceResponse evidence = resolveEvidence(sourceRef, evidenceReferences, evidenceResolver);
            CfsReportDocument.DataSourceItem item = evidence == null
                    ? unresolvedSource()
                    : evidenceSource(evidence, aliases);
            unique.putIfAbsent(sourceKey(item), item);
        }
        for (JsonNode evidence : iterable(root.path("productEvidenceRefs"))) {
            if (!evidence.isObject()) {
                continue;
            }
            String summary = displayText(evidence.path("content").asText(""), aliases);
            String level = evidence.has("score")
                    ? "检索相关度：" + String.format(java.util.Locale.ROOT, "%.2f", evidence.path("score").asDouble())
                    : "内部产品资料";
            CfsReportDocument.DataSourceItem item = new CfsReportDocument.DataSourceItem(
                    "产品知识资料", "内部产品知识库资料", "内部产品知识库", "",
                    StringUtils.hasText(summary) ? summary : "产品资料摘要待补充", level);
            unique.putIfAbsent(sourceKey(item), item);
        }
        if (!rawTextList(root.path("ruleRefs")).isEmpty()) {
            CfsReportDocument.DataSourceItem item = new CfsReportDocument.DataSourceItem(
                    "规则依据", "CFS合规规则集", "内部规则库", "",
                    "报告生成及合规校验所依据的内部规则。", "内部规则");
            unique.putIfAbsent(sourceKey(item), item);
        }
        addInternalSource(unique, inputRefs, "KYC", "客户尽调与画像分析");
        addInternalSource(unique, inputRefs, "市场洞察", "市场与行业洞察分析");
        addInternalSource(unique, inputRefs, "产品专家", "产品专家分析");
        return unique.values().stream().limit(MAX_DATA_SOURCES).toList();
    }

    private Iterable<JsonNode> iterable(JsonNode node) {
        return node != null && node.isArray() ? node : List.of();
    }

    private void addInternalSource(
            Map<String, CfsReportDocument.DataSourceItem> sources,
            Map<String, String> inputRefs,
            String inputLabel,
            String sourceName) {
        if (!inputRefs.containsKey(inputLabel)) {
            return;
        }
        CfsReportDocument.DataSourceItem item = new CfsReportDocument.DataSourceItem(
                "内部分析结果", sourceName, "当前CFS工作流", "",
                "作为本报告分析输入的已确认内部结果。", "内部资料");
        sources.putIfAbsent(sourceKey(item), item);
    }

    private EvidenceResponse resolveEvidence(
            String sourceRef,
            JsonNode evidenceReferences,
            Function<Long, EvidenceResponse> evidenceResolver) {
        if (evidenceResolver == null || evidenceReferences == null || !evidenceReferences.isObject()) {
            return null;
        }
        JsonNode sourceIdNode = evidenceReferences.path(sourceRef);
        if (!sourceIdNode.canConvertToLong()) {
            return null;
        }
        try {
            return evidenceResolver.apply(sourceIdNode.asLong());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private CfsReportDocument.DataSourceItem evidenceSource(
            EvidenceResponse evidence, Map<String, String> aliases) {
        List<String> locatorParts = new ArrayList<>();
        addLocator(locatorParts, evidence.sheetName());
        if (evidence.sourceRowNumber() != null) {
            locatorParts.add("第" + evidence.sourceRowNumber() + "行");
        }
        addLocator(locatorParts, evidence.columnName());
        addLocator(locatorParts, evidence.cellReference());
        addLocator(locatorParts, evidence.sourceLocator());
        return new CfsReportDocument.DataSourceItem(
                "客户与企业数据",
                StringUtils.hasText(evidence.fileName()) ? evidence.fileName() : "客户资料",
                locatorParts.isEmpty() ? "来源位置未提供" : String.join(" / ", locatorParts),
                evidence.sourceDate() == null ? "" : evidence.sourceDate().toString(),
                displayText(evidence.originalText(), aliases),
                StringUtils.hasText(evidence.sourceLevel()) ? evidence.sourceLevel() : "来源级别未提供");
    }

    private CfsReportDocument.DataSourceItem unresolvedSource() {
        return new CfsReportDocument.DataSourceItem(
                "客户与企业数据", "来源信息待补充", "待人工核实", "",
                "该引用尚未解析为具体来源，需在对客使用前完成复核。", "待核实");
    }

    private String sourceKey(CfsReportDocument.DataSourceItem item) {
        return String.join("|", item.sourceType(), item.sourceName(), item.locator(), item.summary());
    }

    private void addLocator(List<String> parts, String value) {
        if (StringUtils.hasText(value) && !parts.contains(value.trim())) {
            parts.add(value.trim());
        }
    }

    private Map<String, String> aliasMappings(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, String> mappings = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            if (entry.getValue().isTextual()
                    && StringUtils.hasText(entry.getKey())
                    && StringUtils.hasText(entry.getValue().asText())) {
                mappings.put(entry.getKey(), entry.getValue().asText().trim());
            }
        });
        return Map.copyOf(mappings);
    }

    private String displayText(String value, Map<String, String> aliases) {
        String result = normalize(value);
        List<Map.Entry<String, String>> orderedAliases = aliases.entrySet().stream()
                .sorted(Map.Entry.<String, String>comparingByKey(
                        Comparator.comparingInt(String::length).reversed()))
                .toList();
        for (Map.Entry<String, String> alias : orderedAliases) {
            result = replaceToken(result, alias.getKey(), alias.getValue());
        }
        Matcher relationMatcher = RELATION_ALIAS.matcher(result);
        StringBuffer relationText = new StringBuffer(result.length());
        while (relationMatcher.find()) {
            String label = switch (relationMatcher.group(1).toUpperCase(java.util.Locale.ROOT)) {
                case "SON" -> "儿子";
                case "DAUGHTER" -> "女儿";
                default -> "配偶";
            };
            relationMatcher.appendReplacement(relationText,
                    Matcher.quoteReplacement(label + relationMatcher.group(2)));
        }
        relationMatcher.appendTail(relationText);
        result = relationText.toString();
        Matcher aliasMatcher = FALLBACK_ALIAS.matcher(result);
        StringBuffer readable = new StringBuffer(result.length());
        while (aliasMatcher.find()) {
            String replacement = fallbackAlias(aliasMatcher.group(1), aliasMatcher.group(2));
            aliasMatcher.appendReplacement(readable, Matcher.quoteReplacement(replacement));
        }
        aliasMatcher.appendTail(readable);
        result = readable.toString();
        for (Map.Entry<String, String> term : DISPLAY_TERMS.entrySet()) {
            result = replaceToken(result, term.getKey(), term.getValue());
        }
        return result.replaceAll("(?i)\\bperson\\s*维度", "个人维度")
                .replaceAll("(?i)\\benterprise\\s*维度", "企业维度")
                .replaceAll("(?i)\\bfamily\\s*维度", "家庭维度")
                .replaceAll("(?i)\\bsocial\\s*维度", "社会维度");
    }

    private String fallbackAlias(String prefix, String number) {
        return switch (prefix) {
            case "P" -> "1".equals(number) ? "客户本人" : "关键人物" + number;
            case "E" -> "关联企业" + number;
            case "F" -> "家庭成员" + number;
            case "O" -> "关联机构" + number;
            default -> "关联对象" + number;
        };
    }

    private String replaceToken(String text, String token, String replacement) {
        return text.replaceAll(
                "(?i)(?<![A-Za-z0-9_-])" + Pattern.quote(token) + "(?![A-Za-z0-9_-])",
                Matcher.quoteReplacement(replacement));
    }

    private String structuredBody(String value) {
        String normalized = normalize(value)
                .replaceAll("(?m)^\\s*[•·*]\\s+", "- ")
                .replaceAll("(?m)^\\s*(\\d+)[、.]\\s*", "- ")
                .replaceAll("(?<!^)\\s*(?=[（(][一二三四五六七八九十][）)])", "\n");
        List<String> lines = normalized.lines().map(String::trim)
                .filter(StringUtils::hasText).toList();
        if (lines.size() == 1 && normalized.length() > 80) {
            List<String> sentences = List.of(normalized.split("(?<=[。；;])\\s*"));
            if (sentences.size() > 1) {
                return sentences.stream().map(String::trim).filter(StringUtils::hasText)
                        .map(sentence -> "- " + sentence).reduce((left, right) -> left + "\n" + right)
                        .orElse(normalized);
            }
        }
        return String.join("\n", lines);
    }

    private void putIfText(Map<String, String> values, String label, JsonNode node) {
        String value = normalize(node.asText(""));
        if (StringUtils.hasText(value)) {
            values.put(label, value);
        }
    }

    private String text(JsonNode root, String field) {
        return normalize(root.path(field).asText(""));
    }

    private List<String> displayTextList(JsonNode node, Map<String, String> aliases) {
        return rawTextList(node).stream().map(value -> displayText(value, aliases)).toList();
    }

    private List<String> rawTextList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        node.forEach(item -> {
            String value = normalize(item.isValueNode() ? item.asText("") : item.toString());
            if (StringUtils.hasText(value)) {
                result.add(value);
            }
        });
        return List.copyOf(result);
    }

    private List<String> productEvidence(JsonNode node, Map<String, String> aliases) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        node.forEach(item -> {
            if (!item.isObject()) {
                String value = displayText(item.asText(""), aliases);
                if (StringUtils.hasText(value)) {
                    result.add(value);
                }
                return;
            }
            String content = displayText(item.path("content").asText(""), aliases);
            if (StringUtils.hasText(content)) {
                result.add(content);
            }
        });
        return List.copyOf(result);
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\_", "_")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim();
    }
}
