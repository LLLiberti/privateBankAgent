package com.privatebank.admin.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.common.exception.BusinessException;
import com.privatebank.common.exception.ErrorCode;
import com.privatebank.config.StorageProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class ConfigurationRegistry {

    private static final java.util.Set<String> ALLOWED_TYPES = java.util.Set.of(
            "PROMPT", "RULE", "MODEL", "TEMPLATE");

    private final ObjectMapper objectMapper;
    private final StorageProperties storageProperties;
    private final Map<String, Candidate> candidates = new ConcurrentHashMap<>();

    public Map<String, Object> current(String type) {
        String normalized = normalize(type);
        Path file = configurationFile(normalized);
        if (!Files.exists(file)) {
            return Map.of("type", normalized, "published", false, "configuration", Map.of());
        }
        try {
            Map<String, Object> value = objectMapper.readValue(file.toFile(), new TypeReference<>() {});
            return mask(value);
        } catch (IOException exception) {
            throw unavailable("配置读取失败");
        }
    }

    public Map<String, Object> validate(String type, Map<String, Object> configuration) {
        String normalized = normalize(type);
        if (configuration.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_ARGUMENT, "候选配置不能为空");
        }
        String candidateId = "CFG-" + UUID.randomUUID();
        candidates.put(candidateId, new Candidate(normalized, Map.copyOf(configuration), Instant.now()));
        return Map.of("candidateId", candidateId, "type", normalized, "valid", true);
    }

    public synchronized Map<String, Object> publish(String type, String candidateId) {
        String normalized = normalize(type);
        Candidate candidate = candidates.get(candidateId);
        if (candidate == null || !candidate.type().equals(normalized)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, "候选配置不存在");
        }
        Path target = configurationFile(normalized);
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("type", normalized);
        stored.put("version", Instant.now().toEpochMilli());
        stored.put("publishedAt", Instant.now().toString());
        stored.put("configuration", candidate.configuration());
        try {
            Files.createDirectories(target.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), stored);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            candidates.remove(candidateId);
            return mask(stored);
        } catch (IOException exception) {
            throw unavailable("配置发布失败");
        }
    }

    public java.util.Set<String> supportedTypes() {
        return ALLOWED_TYPES;
    }

    private String normalize(String type) {
        String normalized = type == null ? "" : type.trim().toUpperCase(java.util.Locale.ROOT);
        if (!ALLOWED_TYPES.contains(normalized)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_ARGUMENT, "不支持的配置类型");
        }
        return normalized;
    }

    private Path configurationFile(String type) {
        return storageProperties.root().toAbsolutePath().normalize()
                .resolve("config").resolve(type.toLowerCase(java.util.Locale.ROOT) + ".json");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mask(Map<String, Object> source) {
        Map<String, Object> masked = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String lower = key.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("secret") || lower.contains("password") || lower.contains("token") || lower.contains("api_key")) {
                masked.put(key, "******");
            } else if (value instanceof Map<?, ?> nested) {
                masked.put(key, mask((Map<String, Object>) nested));
            } else {
                masked.put(key, value);
            }
        });
        return masked;
    }

    private BusinessException unavailable(String message) {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.SERVICE_UNAVAILABLE, message);
    }

    private record Candidate(String type, Map<String, Object> configuration, Instant createdAt) {
    }
}
