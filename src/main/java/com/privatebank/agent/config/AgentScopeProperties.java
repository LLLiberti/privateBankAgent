package com.privatebank.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

@ConfigurationProperties(prefix = "private-bank.agent-runtime")
public record AgentScopeProperties(
        DeepSeek deepseek,
        Integer maxModelRetries,
        Integer maxIterations,
        Integer maxBusinessRepairAttempts) {

    public AgentScopeProperties {
        deepseek = deepseek == null ? new DeepSeek(null, null, null, null) : deepseek;
        maxModelRetries = maxModelRetries == null ? 2 : maxModelRetries;
        maxIterations = maxIterations == null ? 4 : maxIterations;
        maxBusinessRepairAttempts = maxBusinessRepairAttempts == null ? 2 : maxBusinessRepairAttempts;
    }

    public record DeepSeek(
            String baseUrl,
            String apiKey,
            String model,
            Double temperature) {

        public DeepSeek {
            baseUrl = hasText(baseUrl) ? normalizeBaseUrl(baseUrl) : "https://api.deepseek.com/v1";
            apiKey = apiKey == null ? "" : apiKey;
            model = hasText(model) ? model : "deepseek-v4-flash";
            temperature = temperature == null ? 0D : temperature;
        }

        private static String normalizeBaseUrl(String value) {
            String normalized = value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
            URI uri = URI.create(normalized);
            String path = uri.getPath();
            if (path == null || path.isBlank() || "/".equals(path)) {
                return normalized + "/v1";
            }
            return normalized;
        }

        private static boolean hasText(String value) {
            return value != null && !value.isBlank();
        }
    }
}
