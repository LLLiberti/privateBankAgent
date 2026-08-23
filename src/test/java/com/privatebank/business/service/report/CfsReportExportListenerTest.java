package com.privatebank.business.service.report;

import com.privatebank.business.service.workflow.CfsReportExportRequestedEvent;
import com.privatebank.business.service.workflow.WorkflowEventHub;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CfsReportExportListenerTest {

    @Test
    void recordsFailureAndPublishesFailureEvent() {
        CfsReportExportService exportService = mock(CfsReportExportService.class);
        WorkflowEventHub eventHub = mock(WorkflowEventHub.class);
        CfsReportExportRequestedEvent event = new CfsReportExportRequestedEvent(
                "WF-1", "ART-CFS", "ART-COMPLIANCE");
        doThrow(new IllegalStateException("font missing")).when(exportService)
                .export("WF-1", "ART-CFS", "ART-COMPLIANCE");

        new CfsReportExportListener(exportService, eventHub).onExportRequested(event);

        verify(exportService).recordFailure("WF-1", "font missing");
        verify(eventHub).publish(eq("WF-1"), eq("CFS_REPORT_EXPORT_FAILED"), anyMap());
    }
}

