package com.privatebank.business.dto.document;

import com.privatebank.business.entity.document.DocumentRecord;

import java.time.LocalDateTime;

public record DocumentResponse(
        String documentId,
        Long customerId,
        String fileName,
        String fileType,
        Long sourceId,
        LocalDateTime publishTime,
        LocalDateTime uploadTime,
        String parseStatus,
        String failureReason,
        Integer factCount) {

    public static DocumentResponse from(DocumentRecord document) {
        return new DocumentResponse(
                document.getDocumentId(), document.getPersonId(), document.getFileName(), document.getFileType(),
                document.getSourceId(), document.getPublishTime(), document.getUploadTime(),
                document.getParseStatus(), document.getParseErrorMessage(), document.getFactCount());
    }
}
