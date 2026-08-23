package com.privatebank.business.service.report;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class CfsReportDocumentFactory {

    public CfsReportDocument create(
            JsonNode root,
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

        return new CfsReportDocument(
                workflowId,
                cfsArtifactId,
                complianceArtifactId,
                text(root, "customerId"),
                root.path("cfsVersion").asInt(1),
                generatedAt,
                text(structure, "chapter1CustomerInfo"),
                text(structure, "chapter2ServicePlan"),
                text(structure, "chapter3MarketingStrategy"),
                text(root, "marketingStrategy"),
                text(root, "communicationGuide"),
                text(root, "comprehensiveRiskAssessment"),
                textList(structure.path("attachments")),
                textList(root.path("pendingVerificationItems")),
                textList(root.path("estimatedDataItems")),
                textList(root.path("sourceRefs")),
                productEvidence(root.path("productEvidenceRefs")),
                textList(root.path("ruleRefs")),
                Map.copyOf(inputs));
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

    private List<String> textList(JsonNode node) {
        if (!node.isArray()) {
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

    private List<String> productEvidence(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        node.forEach(item -> {
            if (!item.isObject()) {
                String value = normalize(item.asText(""));
                if (StringUtils.hasText(value)) {
                    result.add(value);
                }
                return;
            }
            List<String> parts = new ArrayList<>();
            addPart(parts, "产品", item.path("productId"));
            addPart(parts, "来源", item.path("sourceId"));
            addPart(parts, "文档", item.path("documentId"));
            addPart(parts, "片段", item.path("chunkId"));
            addPart(parts, "内容", item.path("content"));
            if (item.has("score")) {
                parts.add("相关度：" + item.path("score").asDouble());
            }
            if (!parts.isEmpty()) {
                result.add(String.join("；", parts));
            }
        });
        return List.copyOf(result);
    }

    private void addPart(List<String> parts, String label, JsonNode valueNode) {
        String value = normalize(valueNode.asText(""));
        if (StringUtils.hasText(value)) {
            parts.add(label + "：" + value);
        }
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
