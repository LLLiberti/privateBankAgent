package com.privatebank.agent.application.kyc;

import com.privatebank.agent.domain.kyc.KycMaskedInput;
import com.privatebank.agent.domain.kyc.KycOutputValidationException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class KycOutputValidator {

    private static final Set<String> ROOT_FIELDS = Set.of(
            "riskLevel", "summary", "findings", "riskAlerts", "recommendedActions", "dataGaps",
            "graphAssessment");
    private static final Set<String> FINDING_FIELDS = Set.of(
            "dimension", "riskLevel", "finding", "evidenceRefs");
    private static final Set<String> GRAPH_ASSESSMENT_FIELDS = Set.of(
            "contribution", "summary", "evidenceRefs");
    private static final Set<String> RISK_LEVELS = Set.of("LOW", "MEDIUM", "HIGH", "UNKNOWN");
    private static final Set<String> DIMENSIONS = Set.of("PERSON", "ENTERPRISE", "FAMILY", "SOCIAL");
    private static final Set<String> GRAPH_CONTRIBUTIONS = Set.of(
            "INCREMENTAL", "CONFIRMATORY", "NO_INCREMENT", "NOT_AVAILABLE");

    private final ObjectMapper objectMapper;

    public String validate(String rawOutput, KycMaskedInput input) {
        JsonNode root = parse(rawOutput);
        requireObject(root, "根节点必须是 JSON 对象");
        requireExactFields(root, ROOT_FIELDS, "根节点字段不符合 KYC 合约");
        requireEnum(root.path("riskLevel"), RISK_LEVELS, "riskLevel 无效");
        requireText(root.path("summary"), 1200, "summary 无效");
        validateFindings(root.path("findings"), input.evidenceReferences().keySet());
        validateTextArray(root.path("riskAlerts"), 20, 600, "riskAlerts 无效");
        validateTextArray(root.path("recommendedActions"), 20, 600, "recommendedActions 无效");
        validateTextArray(root.path("dataGaps"), 20, 600, "dataGaps 无效");
        validateGraphAssessment(root.path("graphAssessment"), root.path("findings"), input);
        rejectSensitiveText(root, input.prohibitedTerms());
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw new KycOutputValidationException("KYC 结果无法序列化");
        }
    }

    private void validateGraphAssessment(JsonNode assessment, JsonNode findings, KycMaskedInput input) {
        requireObject(assessment, "graphAssessment 必须是对象");
        requireExactFields(assessment, GRAPH_ASSESSMENT_FIELDS, "graphAssessment 字段不符合 KYC 合约");
        requireEnum(assessment.path("contribution"), GRAPH_CONTRIBUTIONS, "graphAssessment.contribution 无效");
        requireText(assessment.path("summary"), 600, "graphAssessment.summary 无效");

        Set<String> graphEvidence = graphEvidence(input);
        JsonNode evidenceRefs = assessment.path("evidenceRefs");
        if (!evidenceRefs.isArray() || evidenceRefs.size() > 20) {
            throw new KycOutputValidationException("graphAssessment.evidenceRefs 无效");
        }
        for (JsonNode evidenceRef : evidenceRefs) {
            if (!evidenceRef.isTextual() || !graphEvidence.contains(evidenceRef.asText())) {
                throw new KycOutputValidationException("graphAssessment 引用了非 Neo4j 关系证据");
            }
        }

        String contribution = assessment.path("contribution").asText();
        if (graphEvidence.isEmpty()) {
            if (!"NOT_AVAILABLE".equals(contribution) || !evidenceRefs.isEmpty()) {
                throw new KycOutputValidationException("Neo4j 关系不可用时 graphAssessment 必须为 NOT_AVAILABLE");
            }
            return;
        }
        if ("NOT_AVAILABLE".equals(contribution)) {
            throw new KycOutputValidationException("Neo4j 关系可用时 graphAssessment 不能为 NOT_AVAILABLE");
        }
        if (!"NO_INCREMENT".equals(contribution) && evidenceRefs.isEmpty()) {
            throw new KycOutputValidationException(
                    "graphAssessment 为 INCREMENTAL 或 CONFIRMATORY 时必须引用 Neo4j 关系证据");
        }
        if ("INCREMENTAL".equals(contribution) && !findingsUseEvidence(findings, graphEvidence)) {
            throw new KycOutputValidationException("Neo4j 标记为 INCREMENTAL 时至少一条 finding 必须引用图关系证据");
        }
    }

    private Set<String> graphEvidence(KycMaskedInput input) {
        Object graphValue = input.payload().get("relationshipGraph");
        if (!(graphValue instanceof java.util.Map<?, ?> graph)) {
            return Set.of();
        }
        Object refsValue = graph.get("evidenceRefs");
        if (!(refsValue instanceof Iterable<?> refs)) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (Object ref : refs) {
            if (ref instanceof String text && input.evidenceReferences().containsKey(text)) {
                result.add(text);
            }
        }
        return Set.copyOf(result);
    }

    private boolean findingsUseEvidence(JsonNode findings, Set<String> evidence) {
        for (JsonNode finding : findings) {
            for (JsonNode evidenceRef : finding.path("evidenceRefs")) {
                if (evidenceRef.isTextual() && evidence.contains(evidenceRef.asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    private JsonNode parse(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            throw new KycOutputValidationException("模型未返回 JSON");
        }
        String cleaned = rawOutput.trim();
        if (cleaned.startsWith("```")) {
            int firstLineEnd = cleaned.indexOf('\n');
            cleaned = firstLineEnd < 0 ? "" : cleaned.substring(firstLineEnd + 1);
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.substring(0, cleaned.length() - 3).trim();
            }
        }
        try {
            return objectMapper.readTree(cleaned);
        } catch (JsonProcessingException exception) {
            throw new KycOutputValidationException("模型返回的内容不是有效 JSON");
        }
    }

    private void validateFindings(JsonNode findings, Set<String> allowedEvidence) {
        if (!findings.isArray() || findings.size() > 20) {
            throw new KycOutputValidationException("findings 必须是最多 20 项的数组");
        }
        for (int findingIndex = 0; findingIndex < findings.size(); findingIndex++) {
            JsonNode finding = findings.get(findingIndex);
            String path = "findings[" + findingIndex + "]";
            requireObject(finding, path + " 必须是对象");
            requireExactFields(finding, FINDING_FIELDS, path + " 字段不符合 KYC 合约");
            requireEnum(finding.path("dimension"), DIMENSIONS, path + ".dimension 无效");
            requireEnum(finding.path("riskLevel"), RISK_LEVELS, path + ".riskLevel 无效");
            requireText(finding.path("finding"), 800, path + ".finding 无效");
            JsonNode evidenceRefs = finding.path("evidenceRefs");
            if (!evidenceRefs.isArray()) {
                throw new KycOutputValidationException(path + ".evidenceRefs 必须是数组");
            }
            if (evidenceRefs.isEmpty()) {
                throw new KycOutputValidationException(path + ".evidenceRefs 至少包含一项证据");
            }
            if (evidenceRefs.size() > 10) {
                throw new KycOutputValidationException(
                        path + ".evidenceRefs 最多 10 项，实际 " + evidenceRefs.size() + " 项");
            }
            for (int evidenceIndex = 0; evidenceIndex < evidenceRefs.size(); evidenceIndex++) {
                JsonNode evidenceRef = evidenceRefs.get(evidenceIndex);
                if (!evidenceRef.isTextual()) {
                    throw new KycOutputValidationException(
                            path + ".evidenceRefs[" + evidenceIndex + "] 必须是 SRC-* 文本");
                }
                if (!allowedEvidence.contains(evidenceRef.asText())) {
                    throw new KycOutputValidationException(
                            path + ".evidenceRefs[" + evidenceIndex + "] 不在允许引用集合中");
                }
            }
        }
    }

    private void validateTextArray(JsonNode value, int maxItems, int maxLength, String message) {
        if (!value.isArray() || value.size() > maxItems) {
            throw new KycOutputValidationException(message);
        }
        for (JsonNode item : value) {
            requireText(item, maxLength, message);
        }
    }

    private void requireExactFields(JsonNode object, Set<String> expected, String message) {
        Set<String> actual = new LinkedHashSet<>();
        Iterator<String> fields = object.fieldNames();
        fields.forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw new KycOutputValidationException(message);
        }
    }

    private void requireObject(JsonNode value, String message) {
        if (!value.isObject()) {
            throw new KycOutputValidationException(message);
        }
    }

    private void requireEnum(JsonNode value, Set<String> allowed, String message) {
        if (!value.isTextual() || !allowed.contains(value.asText())) {
            throw new KycOutputValidationException(message);
        }
    }

    private void requireText(JsonNode value, int maxLength, String message) {
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > maxLength) {
            throw new KycOutputValidationException(message);
        }
    }

    private void rejectSensitiveText(JsonNode value, Set<String> prohibitedTerms) {
        if (value.isTextual()) {
            KycSensitiveTextPolicy.rejectOutput(value.asText(), prohibitedTerms);
            return;
        }
        if (value.isContainerNode()) {
            for (JsonNode child : value) {
                rejectSensitiveText(child, prohibitedTerms);
            }
        }
    }
}
