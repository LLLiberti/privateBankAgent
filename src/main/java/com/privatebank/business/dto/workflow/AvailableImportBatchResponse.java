package com.privatebank.business.dto.workflow;

import java.time.LocalDateTime;

public record AvailableImportBatchResponse(
        Long importBatchId,
        String batchName,
        LocalDateTime importedAt,
        Integer recordCount) {
}
