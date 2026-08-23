package com.privatebank.business.service.report;

import com.privatebank.agent.infrastructure.kyc.KycAsyncConfiguration;
import com.privatebank.business.service.workflow.CfsReportExportRequestedEvent;
import com.privatebank.business.service.workflow.WorkflowEventHub;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class CfsReportExportListener {

    private final CfsReportExportService exportService;
    private final WorkflowEventHub eventHub;

    @Async(KycAsyncConfiguration.KYC_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onExportRequested(CfsReportExportRequestedEvent event) {
        try {
            CfsReportExportService.ExportResult result = exportService.export(
                    event.workflowId(), event.cfsArtifactId(), event.complianceArtifactId());
            eventHub.publish(event.workflowId(), "CFS_REPORT_EXPORT_COMPLETED", Map.of(
                    "workflowId", event.workflowId(),
                    "cfsArtifactId", event.cfsArtifactId(),
                    "complianceArtifactId", event.complianceArtifactId(),
                    "fileCount", result.files().size(),
                    "formats", result.files().stream().map(CfsReportExportService.FileMetadata::format).toList()));
        } catch (Exception exception) {
            log.error("CFS report export failed, workflowId={}, cfsArtifactId={}",
                    event.workflowId(), event.cfsArtifactId(), exception);
            String message = exception.getMessage() == null ? "CFS报告导出失败" : exception.getMessage();
            try {
                exportService.recordFailure(event.workflowId(), message);
            } catch (Exception stateException) {
                log.error("CFS report failure status update failed, workflowId={}",
                        event.workflowId(), stateException);
            }
            eventHub.publish(event.workflowId(), "CFS_REPORT_EXPORT_FAILED", Map.of(
                    "workflowId", event.workflowId(),
                    "cfsArtifactId", event.cfsArtifactId(),
                    "complianceArtifactId", event.complianceArtifactId(),
                    "errorCode", "CFS_REPORT_EXPORT_FAILED",
                    "message", message));
        }
    }
}
