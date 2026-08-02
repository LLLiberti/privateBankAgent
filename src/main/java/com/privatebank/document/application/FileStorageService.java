package com.privatebank.document.application;

import com.privatebank.common.exception.BusinessException;
import com.privatebank.common.exception.ErrorCode;
import com.privatebank.config.StorageProperties;
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
