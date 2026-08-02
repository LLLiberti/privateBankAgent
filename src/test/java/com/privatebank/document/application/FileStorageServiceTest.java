package com.privatebank.document.application;

import com.privatebank.common.exception.BusinessException;
import com.privatebank.common.exception.ErrorCode;
import com.privatebank.config.StorageProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileStorageServiceTest {

    Path storageRoot;
    Path outsideRoot;

    @BeforeEach
    void setUp() throws Exception {
        String suffix = UUID.randomUUID().toString();
        storageRoot = Path.of("target", "test-storage", suffix).toAbsolutePath().normalize();
        outsideRoot = Path.of("target", "test-outside", suffix).toAbsolutePath().normalize();
        Files.createDirectories(storageRoot);
        Files.createDirectories(outsideRoot);
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(storageRoot.resolve("result.pdf"));
        Files.deleteIfExists(outsideRoot.resolve("secret.txt"));
        Files.deleteIfExists(storageRoot);
        Files.deleteIfExists(outsideRoot);
    }

    @Test
    void resolvesRegularFileWithinConfiguredStorage() throws Exception {
        Path file = Files.writeString(storageRoot.resolve("result.pdf"), "result");
        FileStorageService service = service();

        assertThat(service.resolveStoredFile(file.toString())).isEqualTo(file.toAbsolutePath().normalize());
    }

    @Test
    void rejectsFileOutsideConfiguredStorage() throws Exception {
        Path file = Files.writeString(outsideRoot.resolve("secret.txt"), "secret");
        FileStorageService service = service();

        assertThatThrownBy(() -> service.resolveStoredFile(file.toString()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(ErrorCode.ACCESS_DENIED));
    }

    private FileStorageService service() {
        return new FileStorageService(new StorageProperties(storageRoot, 1024));
    }
}
