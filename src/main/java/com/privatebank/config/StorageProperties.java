package com.privatebank.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "private-bank.storage")
public record StorageProperties(Path root, long maxFileSizeBytes) {
}
