package com.privatebank.business.dto.workflow;

public record CfsReportFileResponse(
        String fileId,
        String format,
        String fileName,
        String contentType,
        Long sizeBytes,
        String generatedAt) {
}
