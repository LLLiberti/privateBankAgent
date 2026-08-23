package com.privatebank.business.service.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.business.entity.workflow.AgentArtifact;
import com.privatebank.business.entity.workflow.WorkflowState;
import com.privatebank.business.enums.workflow.AgentType;
import com.privatebank.business.enums.workflow.WorkflowStatus;
import com.privatebank.business.mapper.workflow.AgentArtifactMapper;
import com.privatebank.business.mapper.workflow.WorkflowStateMapper;
import com.privatebank.business.service.document.FileStorageService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CfsReportExportServiceTest {

    @Test
    void exportsAllFormatsAndWritesFileMetadataBackToCfsArtifact() throws Exception {
        AgentArtifactMapper mapper = mock(AgentArtifactMapper.class);
        WorkflowStateMapper workflowMapper = mock(WorkflowStateMapper.class);
        FileStorageService storage = mock(FileStorageService.class);
        CfsMarkdownRenderer markdown = mock(CfsMarkdownRenderer.class);
        CfsDocxRenderer docx = mock(CfsDocxRenderer.class);
        CfsPdfRenderer pdf = mock(CfsPdfRenderer.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        AgentArtifact cfs = artifact("ART-CFS", AgentType.SOLUTION_DESIGN, cfsJson(), null);
        AgentArtifact compliance = artifact(
                "ART-COMPLIANCE", AgentType.COMPLIANCE_CHECK,
                "{\"cfsArtifactRef\":\"ART-CFS\"}", "REVIEW_REQUIRED");
        WorkflowState workflow = workflow();
        when(workflowMapper.selectById("WF-1")).thenReturn(workflow);
        when(workflowMapper.updateById(workflow)).thenReturn(1);
        when(mapper.selectById("ART-CFS")).thenReturn(cfs);
        when(mapper.selectById("ART-COMPLIANCE")).thenReturn(compliance);
        when(mapper.updateById(cfs)).thenReturn(1);
        when(markdown.render(any())).thenReturn("markdown".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(docx.render(any())).thenReturn(new byte[]{1, 2, 3});
        when(pdf.render(any())).thenReturn(new byte[]{4, 5, 6});
        when(storage.storeGenerated(anyString(), anyString(), anyString(), any(byte[].class)))
                .thenAnswer(invocation -> new FileStorageService.StoredFile(
                        "C:/storage/" + invocation.getArgument(2, String.class),
                        "REPORT", invocation.getArgument(2, String.class)));
        CfsReportExportService service = new CfsReportExportService(
                mapper, workflowMapper, objectMapper, storage, new CfsReportDocumentFactory(), markdown, docx, pdf);

        CfsReportExportService.ExportResult result = service.export(
                "WF-1", "ART-CFS", "ART-COMPLIANCE");

        assertThat(result.files()).extracting(CfsReportExportService.FileMetadata::format)
                .containsExactly("MARKDOWN", "WORD", "PDF");
        JsonNode updated = objectMapper.readTree(cfs.getResult());
        assertThat(updated.path("files")).hasSize(3);
        assertThat(updated.path("files").get(0).path("generator").asText())
                .isEqualTo(CfsReportExportService.GENERATOR);
        assertThat(updated.path("cfsStructure").path("chapter1CustomerInfo").asText())
                .isEqualTo("客户概况");
        assertThat(workflow.getWorkflowStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(workflow.getFinishTime()).isNotNull();
        verify(mapper).updateById(cfs);
        verify(workflowMapper).updateById(workflow);
    }

    @Test
    void recordsFailureAndKeepsWorkflowRetryable() {
        AgentArtifactMapper mapper = mock(AgentArtifactMapper.class);
        WorkflowStateMapper workflowMapper = mock(WorkflowStateMapper.class);
        WorkflowState workflow = workflow();
        when(workflowMapper.selectById("WF-1")).thenReturn(workflow);
        when(workflowMapper.updateById(workflow)).thenReturn(1);
        CfsReportExportService service = new CfsReportExportService(
                mapper, workflowMapper, new ObjectMapper(), mock(FileStorageService.class),
                new CfsReportDocumentFactory(), mock(CfsMarkdownRenderer.class),
                mock(CfsDocxRenderer.class), mock(CfsPdfRenderer.class));

        service.recordFailure("WF-1", "font missing");

        assertThat(workflow.getWorkflowStatus()).isEqualTo(WorkflowStatus.GENERATING_OUTPUT);
        assertThat(workflow.getErrorCode()).isEqualTo("CFS_REPORT_EXPORT_FAILED");
        assertThat(workflow.getErrorMessage()).isEqualTo("font missing");
        assertThat(workflow.getFinishTime()).isNull();
        verify(workflowMapper).updateById(workflow);
    }

    private WorkflowState workflow() {
        WorkflowState workflow = new WorkflowState();
        workflow.setWorkflowId("WF-1");
        workflow.setWorkflowStatus(WorkflowStatus.GENERATING_OUTPUT);
        workflow.setVersion(0L);
        return workflow;
    }

    private AgentArtifact artifact(String id, AgentType type, String result, String complianceResult) {
        AgentArtifact artifact = new AgentArtifact();
        artifact.setArtifactId(id);
        artifact.setWorkflowId("WF-1");
        artifact.setAgentType(type);
        artifact.setResult(result);
        artifact.setComplianceResult(complianceResult);
        return artifact;
    }

    private String cfsJson() {
        return """
                {
                  "customerId":"P-1",
                  "cfsVersion":1,
                  "cfsStructure":{
                    "chapter1CustomerInfo":"客户概况",
                    "chapter2ServicePlan":"服务方案",
                    "chapter3MarketingStrategy":"营销策略",
                    "attachments":[]
                  },
                  "sourceRefs":[],
                  "productEvidenceRefs":[],
                  "ruleRefs":[],
                  "pendingVerificationItems":[],
                  "estimatedDataItems":[],
                  "inputArtifactRefs":{}
                }
                """;
    }
}
