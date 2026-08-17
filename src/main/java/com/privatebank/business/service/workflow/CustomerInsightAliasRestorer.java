package com.privatebank.business.service.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.privatebank.business.dto.workflow.CustomerInsightAnalysisResponse.Analysis;
import com.privatebank.business.dto.workflow.CustomerInsightAnalysisResponse.Finding;
import com.privatebank.business.dto.workflow.CustomerInsightAnalysisResponse.GraphAssessment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Restores persisted KYC runtime aliases only in customer-facing natural-language fields. */
@Component
@Slf4j
public class CustomerInsightAliasRestorer {

    private static final Pattern ALIAS_TOKEN = Pattern.compile(
            "(?<![A-Za-z0-9_-])(?:P|E|F|O|C|V|M|N)-[1-9][0-9]*(?![A-Za-z0-9_-])");

    public Analysis restore(Analysis analysis, JsonNode mappingsNode, String artifactId) {
        if (analysis == null) {
            return null;
        }
        Map<String, String> mappings = readMappings(mappingsNode, artifactId);
        if (mappings.isEmpty()) {
            return analysis;
        }
        return new Analysis(
                analysis.riskLevel(),
                restoreText(analysis.summary(), mappings),
                restoreFindings(analysis.findings(), mappings),
                restoreTexts(analysis.riskAlerts(), mappings),
                restoreTexts(analysis.recommendedActions(), mappings),
                restoreTexts(analysis.dataGaps(), mappings),
                restoreGraphAssessment(analysis.graphAssessment(), mappings));
    }

    private Map<String, String> readMappings(JsonNode mappingsNode, String artifactId) {
        if (mappingsNode == null || mappingsNode.isMissingNode() || mappingsNode.isNull()) {
            return Map.of();
        }
        if (!mappingsNode.isObject()) {
            log.warn("Ignoring non-object aliasMappings for customer-insight artifact {}", artifactId);
            return Map.of();
        }
        Map<String, String> mappings = new LinkedHashMap<>();
        int ignoredEntries = 0;
        var fields = mappingsNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            JsonNode value = entry.getValue();
            if (!ALIAS_TOKEN.matcher(entry.getKey()).matches()
                    || value == null || !value.isTextual() || value.asText().isBlank()) {
                ignoredEntries++;
                continue;
            }
            mappings.put(entry.getKey(), value.asText().trim());
        }
        if (ignoredEntries > 0) {
            log.warn("Ignored {} invalid aliasMappings entries for customer-insight artifact {}",
                    ignoredEntries, artifactId);
        }
        return mappings;
    }

    private List<Finding> restoreFindings(List<Finding> findings, Map<String, String> mappings) {
        if (findings == null) {
            return null;
        }
        return findings.stream().map(finding -> finding == null ? null : new Finding(
                finding.dimension(),
                finding.riskLevel(),
                restoreText(finding.finding(), mappings),
                finding.evidenceRefs())).toList();
    }

    private List<String> restoreTexts(List<String> texts, Map<String, String> mappings) {
        if (texts == null) {
            return null;
        }
        return texts.stream().map(text -> restoreText(text, mappings)).toList();
    }

    private GraphAssessment restoreGraphAssessment(
            GraphAssessment assessment, Map<String, String> mappings) {
        if (assessment == null) {
            return null;
        }
        return new GraphAssessment(
                assessment.contribution(),
                restoreText(assessment.summary(), mappings),
                assessment.evidenceRefs());
    }

    private String restoreText(String text, Map<String, String> mappings) {
        if (text == null || text.isBlank()) {
            return text;
        }
        Matcher matcher = ALIAS_TOKEN.matcher(text);
        StringBuffer restored = new StringBuffer(text.length());
        while (matcher.find()) {
            String alias = matcher.group();
            String replacement = mappings.getOrDefault(alias, alias);
            matcher.appendReplacement(restored, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(restored);
        return restored.toString();
    }
}
