package com.privatebank.agent.application.kycchat;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Normalizes a newly masked snapshot to the aliases persisted with the selected KYC artifact. */
@Component
public class KycChatAliasNormalizer {

    private static final Pattern ALIAS = Pattern.compile("([A-Z]+)-(\\d+)");
    private static final Pattern ALIAS_IN_TEXT = Pattern.compile("(?<![A-Za-z0-9])([A-Z]+-\\d+)(?![A-Za-z0-9])");

    public AliasPlan plan(
            Map<String, String> currentMappings,
            Map<String, String> canonicalMappings) {
        Map<String, String> canonical = new LinkedHashMap<>(canonicalMappings);
        Map<String, String> canonicalByValue = new LinkedHashMap<>();
        Map<String, Integer> maxSequenceByPrefix = new LinkedHashMap<>();
        canonical.forEach((alias, raw) -> {
            canonicalByValue.putIfAbsent(mappingKey(alias, raw), alias);
            Matcher matcher = ALIAS.matcher(alias);
            if (matcher.matches()) {
                maxSequenceByPrefix.merge(matcher.group(1), Integer.parseInt(matcher.group(2)), Math::max);
            }
        });

        Map<String, String> replacements = new LinkedHashMap<>();
        currentMappings.forEach((currentAlias, raw) -> {
            String canonicalAlias = canonicalByValue.get(mappingKey(currentAlias, raw));
            if (canonicalAlias == null) {
                canonicalAlias = allocate(currentAlias, canonical, maxSequenceByPrefix);
                canonical.put(canonicalAlias, raw);
                canonicalByValue.put(mappingKey(canonicalAlias, raw), canonicalAlias);
            }
            replacements.put(currentAlias, canonicalAlias);
        });
        return new AliasPlan(Map.copyOf(replacements), Map.copyOf(canonical));
    }

    public Map<String, Object> normalizePayload(Map<String, Object> payload, AliasPlan plan) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        payload.forEach((key, value) -> normalized.put(key, normalizeValue(value, plan.replacements())));
        return normalized;
    }

    public String normalizeText(String text, AliasPlan plan) {
        return replaceAliases(text, plan.replacements());
    }

    private Object normalizeValue(Object value, Map<String, String> replacements) {
        if (value instanceof String text) {
            return replaceAliases(text, replacements);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), normalizeValue(item, replacements)));
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            list.forEach(item -> result.add(normalizeValue(item, replacements)));
            return result;
        }
        return value;
    }

    private String replaceAliases(String text, Map<String, String> replacements) {
        if (text == null || text.isEmpty() || replacements.isEmpty()) {
            return text;
        }
        Matcher matcher = ALIAS_IN_TEXT.matcher(text);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String replacement = replacements.getOrDefault(matcher.group(1), matcher.group(1));
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String allocate(
            String currentAlias,
            Map<String, String> canonical,
            Map<String, Integer> maxSequenceByPrefix) {
        Matcher matcher = ALIAS.matcher(currentAlias);
        String prefix = matcher.matches() ? matcher.group(1) : "N";
        if (!canonical.containsKey(currentAlias)) {
            if (matcher.matches()) {
                maxSequenceByPrefix.merge(prefix, Integer.parseInt(matcher.group(2)), Math::max);
            }
            return currentAlias;
        }
        int next = maxSequenceByPrefix.getOrDefault(prefix, 0) + 1;
        String candidate;
        do {
            candidate = prefix + "-" + next++;
        } while (canonical.containsKey(candidate));
        maxSequenceByPrefix.put(prefix, next - 1);
        return candidate;
    }

    private String mappingKey(String alias, String raw) {
        Matcher matcher = ALIAS.matcher(alias);
        String category = matcher.matches() ? matcher.group(1) : "N";
        return category + "\u0000" + normalizeRaw(raw);
    }

    private String normalizeRaw(String raw) {
        return raw == null ? "" : raw.trim().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    public record AliasPlan(
            Map<String, String> replacements,
            Map<String, String> canonicalMappings) {
    }
}
