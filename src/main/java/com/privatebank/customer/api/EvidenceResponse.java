package com.privatebank.customer.api;

import java.time.LocalDate;

public record EvidenceResponse(
        Long sourceRef,
        String fileName,
        String sheetName,
        Integer sourceRowNumber,
        String columnName,
        String cellReference,
        String originalText,
        String sourceLevel,
        LocalDate sourceDate,
        String sourceLocator) {
}
