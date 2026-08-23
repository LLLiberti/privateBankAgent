package com.privatebank.business.service.workflow;

public record CfsReportExportRequestedEvent(
        String workflowId,
        String cfsArtifactId,
        String complianceArtifactId) {
}
