package com.privatebank.business.dto.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.privatebank.business.enums.workflow.CfsReportStatus;
import com.privatebank.business.enums.workflow.WorkflowStatus;

import java.time.LocalDateTime;
import java.util.List;

public record CfsReportPreviewResponse(
        String workflowId,
        Long customerId,
        String customerName,
        WorkflowStatus workflowStatus,
        CfsReportStatus reportStatus,
        String cfsArtifactId,
        Integer versionNo,
        LocalDateTime cfsGeneratedAt,
        String complianceArtifactId,
        String complianceResult,
        LocalDateTime complianceCheckedAt,
        boolean canReview,
        boolean canExport,
        List<CfsReportFileResponse> files,
        String reportExportedAt,
        JsonNode content) {
}
