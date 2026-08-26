package com.privatebank.agent.application.kyc;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Restores KYC runtime aliases at an authorized display boundary. */
@Component
public class KycAliasTextRestorer {

    private static final Pattern ALIAS_TOKEN = Pattern.compile(
            "(?<![A-Za-z0-9_-])(?:P|E|F|O|C|V|M|N)-[1-9][0-9]*(?![A-Za-z0-9_-])");

    public boolean isAliasToken(String value) {
        return value != null && ALIAS_TOKEN.matcher(value).matches();
    }

    public Map<String, String> validateMappings(Map<String, String> mappings) {
        if (mappings == null || mappings.isEmpty()) {
            return Map.of();
        }
        Map<String, String> validated = new LinkedHashMap<>();
        mappings.forEach((alias, raw) -> {
            if (isAliasToken(alias) && raw != null && !raw.isBlank()) {
                validated.put(alias, raw.trim());
            }
        });
        return Map.copyOf(validated);
    }

    public String restoreText(String text, Map<String, String> mappings) {
        if (text == null || text.isBlank() || mappings == null || mappings.isEmpty()) {
            return text;
        }
        return restorePrefix(text, text.length(), mappings);
    }

    public StreamingRestorer streaming(Map<String, String> mappings) {
        return new StreamingRestorer(validateMappings(mappings));
    }

    private String restorePrefix(String source, int endExclusive, Map<String, String> mappings) {
        Matcher matcher = ALIAS_TOKEN.matcher(source);
        StringBuffer restored = new StringBuffer(Math.max(endExclusive, 16));
        int cursor = 0;
        while (matcher.find()) {
            if (matcher.start() >= endExclusive || matcher.end() > endExclusive) {
                break;
            }
            restored.append(source, cursor, matcher.start());
            restored.append(mappings.getOrDefault(matcher.group(), matcher.group()));
            cursor = matcher.end();
        }
        restored.append(source, cursor, endExclusive);
        return restored.toString();
    }

    /** Stateful, append-only restoration for model deltas that may split an alias token. */
    public final class StreamingRestorer {

        private final Map<String, String> mappings;
        private final int maxAliasLength;
        private String pending = "";
        private boolean finished;

        private StreamingRestorer(Map<String, String> mappings) {
            this.mappings = mappings;
            this.maxAliasLength = mappings.keySet().stream()
                    .mapToInt(String::length)
                    .max()
                    .orElse(0);
        }

        public String accept(String delta) {
            if (finished) {
                throw new IllegalStateException("流式别名反脱敏已经结束");
            }
            if (delta == null || delta.isEmpty()) {
                return "";
            }
            String source = pending + delta;
            int holdFrom = potentialAliasSuffixStart(source);
            int safeEnd = holdFrom >= 0 ? holdFrom : source.length();
            String restored = restorePrefix(source, safeEnd, mappings);
            pending = source.substring(safeEnd);
            return restored;
        }

        public String finish() {
            if (finished) {
                return "";
            }
            finished = true;
            String restored = restoreText(pending, mappings);
            pending = "";
            return restored;
        }

        private int potentialAliasSuffixStart(String source) {
            if (maxAliasLength == 0 || source.isEmpty()) {
                return -1;
            }
            int firstCandidate = Math.max(0, source.length() - maxAliasLength);
            for (int index = firstCandidate; index < source.length(); index++) {
                if (!hasAliasLeftBoundary(source, index)) {
                    continue;
                }
                String suffix = source.substring(index);
                if (mappings.keySet().stream().anyMatch(alias -> alias.startsWith(suffix))) {
                    return index;
                }
            }
            return -1;
        }

        private boolean hasAliasLeftBoundary(String source, int index) {
            return index == 0 || !isAliasWordCharacter(source.charAt(index - 1));
        }

        private boolean isAliasWordCharacter(char value) {
            return value >= 'A' && value <= 'Z'
                    || value >= 'a' && value <= 'z'
                    || value >= '0' && value <= '9'
                    || value == '_'
                    || value == '-';
        }
    }
}
