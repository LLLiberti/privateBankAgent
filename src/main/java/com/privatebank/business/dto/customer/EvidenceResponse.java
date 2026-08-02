package com.privatebank.business.dto.customer;

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
