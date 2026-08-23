package com.privatebank.business.dto.workflow;

import com.privatebank.business.enums.workflow.CfsReportStatus;
import com.privatebank.business.enums.workflow.WorkflowStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record CfsReportCenterItemResponse(
        String workflowId,
        Long customerId,
        String customerName,
        WorkflowStatus workflowStatus,
        CfsReportStatus reportStatus,
        String templateId,
        LocalDate asOfDate,
        String cfsArtifactId,
        Integer versionNo,
        String complianceArtifactId,
        String complianceResult,
        boolean canPreview,
        boolean canReview,
        boolean canExport,
        boolean canRetryExport,
        List<CfsReportFileResponse> files,
        String reportExportedAt,
        String errorCode,
        String errorMessage,
        LocalDateTime updatedAt) {
}
