package com.privatebank.business.service.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.business.common.exception.BusinessException;
import com.privatebank.business.common.exception.ErrorCode;
import com.privatebank.business.common.idempotency.IdempotencyExecutor;
import com.privatebank.business.entity.workflow.AgentArtifact;
import com.privatebank.business.entity.workflow.WorkflowState;
import com.privatebank.business.enums.workflow.WorkflowStatus;
import com.privatebank.business.mapper.customer.CustomerDataMapper;
import com.privatebank.business.mapper.workflow.AgentArtifactMapper;
import com.privatebank.business.mapper.workflow.AgentStateMapper;
import com.privatebank.business.mapper.workflow.ImportBatchMapper;
import com.privatebank.business.mapper.workflow.WorkflowReviewMapper;
import com.privatebank.business.mapper.workflow.WorkflowStateMapper;
import com.privatebank.business.security.CurrentUserPrincipal;
import com.privatebank.business.security.CurrentUserService;
import com.privatebank.business.service.document.FileStorageService;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowReportRenderingTest {

    @Test
    void extractsRenderableReportFilesFromCfsArtifact() {
        Fixture fixture = fixture();
        when(fixture.workflowMapper.selectById("WF-1")).thenReturn(workflow(WorkflowStatus.COMPLETED));
        when(fixture.artifactMapper.selectOne(any())).thenReturn(cfsArtifact("""
                {"files":[
                  {"fileId":"FILE-PDF","path":"/storage/WF-1/report.pdf",
                   "fileName":"客户综合服务方案.pdf","contentType":"application/pdf"},
                  {"fileId":"FILE-DOCX","path":"/storage/WF-1/report.docx",
                   "fileName":"客户综合服务方案.docx","contentType":"application/vnd.openxmlformats-officedocument.wordprocessingml.document"}
                ]}
                """));

        var result = fixture.service.result(principal(), "WF-1");

        assertThat(result.finalArtifactId()).isEqualTo("ART-CFS");
        assertThat(result.versionNo()).isEqualTo(2);
        assertThat(result.files()).hasSize(2);
        assertThat(result.files().getFirst()).containsEntry("fileId", "FILE-PDF")
                .containsEntry("contentType", "application/pdf");
    }

    @Test
    void returnsEmptyFileListForMalformedOrMissingFilesNode() {
        Fixture fixture = fixture();
        when(fixture.workflowMapper.selectById("WF-1")).thenReturn(workflow(WorkflowStatus.GENERATING_OUTPUT));
        when(fixture.artifactMapper.selectOne(any())).thenReturn(cfsArtifact("{\"files\":\"not-an-array\"}"));

        assertThat(fixture.service.result(principal(), "WF-1").files()).isEmpty();
    }

    @Test
    void rejectsResultBeforeOutputGenerationStarts() {
        Fixture fixture = fixture();
        when(fixture.workflowMapper.selectById("WF-1")).thenReturn(workflow(WorkflowStatus.WAITING_REVIEW));

        assertThatThrownBy(() -> fixture.service.result(principal(), "WF-1"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(ErrorCode.PRECONDITION_FAILED));

        verify(fixture.artifactMapper, never()).selectOne(any());
    }

    @Test
    void downloadsSelectedReportFileThroughStorageBoundary() {
        Fixture fixture = fixture();
        when(fixture.workflowMapper.selectById("WF-1")).thenReturn(workflow(WorkflowStatus.COMPLETED));
        when(fixture.artifactMapper.selectOne(any())).thenReturn(cfsArtifact("""
                {"files":[{"fileId":"FILE-PDF","path":"/storage/WF-1/report.pdf",
                "fileName":"report.pdf","contentType":"application/pdf"}]}
                """));
        Path storedPath = Path.of("report.pdf");
        when(fixture.fileStorageService.resolveStoredFile(any())).thenReturn(storedPath);

        WorkflowService.DownloadFile file = fixture.service.download(principal(), "WF-1", "FILE-PDF");

        assertThat(file.fileName()).isEqualTo("report.pdf");
        assertThat(file.contentType()).isEqualTo("application/pdf");
        assertThat(file.resource().getFilename()).isEqualTo("report.pdf");
        verify(fixture.fileStorageService).resolveStoredFile("/storage/WF-1/report.pdf");
    }

    @Test
    void rejectsUnknownReportFileId() {
        Fixture fixture = fixture();
        when(fixture.workflowMapper.selectById("WF-1")).thenReturn(workflow(WorkflowStatus.COMPLETED));
        when(fixture.artifactMapper.selectOne(any())).thenReturn(cfsArtifact(
                "{\"files\":[{\"fileId\":\"FILE-PDF\",\"path\":\"/storage/report.pdf\"}]}"));

        assertThatThrownBy(() -> fixture.service.download(principal(), "WF-1", "FILE-MISSING"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private Fixture fixture() {
        WorkflowStateMapper workflowMapper = mock(WorkflowStateMapper.class);
        AgentStateMapper agentStateMapper = mock(AgentStateMapper.class);
        AgentArtifactMapper artifactMapper = mock(AgentArtifactMapper.class);
        CurrentUserService currentUserService = mock(CurrentUserService.class);
        FileStorageService fileStorageService = mock(FileStorageService.class);
        WorkflowService service = new WorkflowService(
                workflowMapper,
                agentStateMapper,
                artifactMapper,
                mock(WorkflowAgentStateService.class),
                mock(WorkflowReviewMapper.class),
                mock(CustomerDataMapper.class),
                mock(ImportBatchMapper.class),
                currentUserService,
                new IdempotencyExecutor(180),
                mock(WorkflowEventHub.class),
                mock(org.springframework.context.ApplicationEventPublisher.class),
                new ObjectMapper().findAndRegisterModules(),
                fileStorageService,
                new CustomerInsightAliasRestorer());
        return new Fixture(service, workflowMapper, artifactMapper, fileStorageService);
    }

    private WorkflowState workflow(WorkflowStatus status) {
        WorkflowState workflow = new WorkflowState();
        workflow.setWorkflowId("WF-1");
        workflow.setPersonId(100L);
        workflow.setWorkflowStatus(status);
        return workflow;
    }

    private AgentArtifact cfsArtifact(String result) {
        AgentArtifact artifact = new AgentArtifact();
        artifact.setArtifactId("ART-CFS");
        artifact.setAgentType(com.privatebank.business.enums.workflow.AgentType.SOLUTION_DESIGN);
        artifact.setVersion(2);
        artifact.setResult(result);
        return artifact;
    }

    private CurrentUserPrincipal principal() {
        return new CurrentUserPrincipal("U-1", "manager", com.privatebank.business.enums.auth.RoleName.CUSTOMER_MANAGER);
    }

    private record Fixture(
            WorkflowService service,
            WorkflowStateMapper workflowMapper,
            AgentArtifactMapper artifactMapper,
            FileStorageService fileStorageService) {
    }
}
