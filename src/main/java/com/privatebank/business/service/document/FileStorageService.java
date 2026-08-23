package com.privatebank.business.service.document;

import com.privatebank.business.common.exception.BusinessException;
import com.privatebank.business.common.exception.ErrorCode;
import com.privatebank.business.config.StorageProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class FileStorageService {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "pdf", "doc", "docx", "xls", "xlsx", "csv");

    private final StorageProperties properties;

    public StoredFile store(Long personId, String documentId, MultipartFile file) {
        validate(file);
        String originalName = StringUtils.cleanPath(file.getOriginalFilename() == null
                ? "upload"
                : file.getOriginalFilename());
        String safeName = Path.of(originalName).getFileName().toString()
                .replaceAll("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5]", "_");
        String extension = extension(safeName);
        Path root = properties.root().toAbsolutePath().normalize();
        Path directory = root.resolve(personId == null ? "knowledge" : personId.toString()).resolve(documentId);
        Path target = directory.resolve(safeName).normalize();
        if (!target.startsWith(directory.normalize())) {
            throw invalid("文件路径无效");
        }
        try {
            Files.createDirectories(directory);
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return new StoredFile(target.toString(), extension.toUpperCase(Locale.ROOT), safeName);
        } catch (IOException exception) {
            throw new BusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.SERVICE_UNAVAILABLE, "文件保存失败");
        }
    }

    /**
     * Stores a generated report below the controlled storage root.
     * Stable workflow/artifact paths make retries replace the same files.
     */
    public StoredFile storeGenerated(
            String workflowId, String artifactId, String fileName, byte[] content) {
        if (!StringUtils.hasText(workflowId) || !StringUtils.hasText(artifactId)) {
            throw invalid("\u5de5\u4f5c\u6d41\u548cArtifact\u6807\u8bc6\u4e0d\u80fd\u4e3a\u7a7a");
        }
        if (content == null || content.length == 0) {
            throw invalid("\u62a5\u544a\u5185\u5bb9\u4e0d\u80fd\u4e3a\u7a7a");
        }
        if (content.length > properties.maxFileSizeBytes()) {
            throw invalid("\u62a5\u544a\u6587\u4ef6\u8d85\u8fc7\u5927\u5c0f\u9650\u5236");
        }

        String safeWorkflowId = safeSegment(workflowId);
        String safeArtifactId = safeSegment(artifactId);
        String safeName = safeFileName(fileName);
        String extension = extension(safeName);
        if (!Set.of("md", "docx", "pdf").contains(extension)) {
            throw invalid("\u4ec5\u652f\u6301Markdown\u3001Word\u548cPDF\u62a5\u544a");
        }

        Path root = properties.root().toAbsolutePath().normalize();
        Path directory = root.resolve("reports").resolve(safeWorkflowId).resolve(safeArtifactId).normalize();
        Path target = directory.resolve(safeName).normalize();
        if (!target.startsWith(directory) || !directory.startsWith(root)) {
            throw invalid("\u62a5\u544a\u6587\u4ef6\u8def\u5f84\u65e0\u6548");
        }
        try {
            Files.createDirectories(directory);
            Files.write(target, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return new StoredFile(target.toString(), extension.toUpperCase(Locale.ROOT), safeName);
        } catch (IOException exception) {
            throw new BusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.SERVICE_UNAVAILABLE, "\u62a5\u544a\u6587\u4ef6\u4fdd\u5b58\u5931\u8d25");
        }
    }

    public void deleteQuietly(String filePath) {
        if (!StringUtils.hasText(filePath)) {
            return;
        }
        Path root = properties.root().toAbsolutePath().normalize();
        Path target = Path.of(filePath).toAbsolutePath().normalize();
        if (!target.startsWith(root)) {
            return;
        }
        try {
            Files.deleteIfExists(target);
        } catch (IOException ignored) {
            // Database failure remains the primary exception; orphan cleanup can be handled operationally.
        }
    }

    public Path resolveStoredFile(String filePath) {
        if (!StringUtils.hasText(filePath)) {
            throw invalid("文件路径不能为空");
        }
        Path root = properties.root().toAbsolutePath().normalize();
        Path target = Path.of(filePath).toAbsolutePath().normalize();
        if (!target.startsWith(root)) {
            throw new BusinessException(
                    HttpStatus.FORBIDDEN, ErrorCode.ACCESS_DENIED, "文件不在受控存储目录中");
        }
        if (!Files.isRegularFile(target)) {
            throw new BusinessException(
                    HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, "文件不存在");
        }
        return target;
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw invalid("上传文件不能为空");
        }
        if (file.getSize() > properties.maxFileSizeBytes()) {
            throw invalid("上传文件超过大小限制");
        }
        String name = file.getOriginalFilename();
        String extension = extension(name == null ? "" : name);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw invalid("仅支持PDF、Word、Excel和CSV文件");
        }
    }

    private String safeSegment(String value) {
        String safe = value.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (!StringUtils.hasText(safe) || ".".equals(safe) || "..".equals(safe)) {
            throw invalid("\u6807\u8bc6\u5305\u542b\u975e\u6cd5\u5b57\u7b26");
        }
        return safe;
    }

    private String safeFileName(String value) {
        String original = StringUtils.cleanPath(StringUtils.hasText(value) ? value : "report");
        String safe = Path.of(original).getFileName().toString()
                .replaceAll("[^\\p{L}\\p{N}._-]", "_");
        if (!StringUtils.hasText(safe)) {
            throw invalid("\u62a5\u544a\u6587\u4ef6\u540d\u65e0\u6548");
        }
        return safe;
    }

    private String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private BusinessException invalid(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_ARGUMENT, message);
    }

    public record StoredFile(String path, String fileType, String fileName) {
    }
}
