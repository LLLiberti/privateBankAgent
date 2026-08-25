package com.privatebank.business.service.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.business.common.exception.BusinessException;
import com.privatebank.business.common.exception.ErrorCode;
import com.privatebank.business.common.idempotency.IdempotencyExecutor;
import com.privatebank.business.dto.admin.AdminWorkflowDeleteRequest;
import com.privatebank.business.entity.workflow.AgentArtifact;
import com.privatebank.business.entity.workflow.WorkflowState;
import com.privatebank.business.enums.auth.RoleName;
import com.privatebank.business.enums.workflow.WorkflowStatus;
import com.privatebank.business.mapper.workflow.AgentArtifactMapper;
import com.privatebank.business.mapper.workflow.AgentStateMapper;
import com.privatebank.business.mapper.workflow.WorkflowReviewMapper;
import com.privatebank.business.mapper.workflow.WorkflowStateMapper;
import com.privatebank.business.security.CurrentUserPrincipal;
import com.privatebank.business.service.document.FileStorageService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminWorkflowCleanupServiceTest {

    private final WorkflowStateMapper workflowMapper = mock(WorkflowStateMapper.class);
    private final AgentStateMapper agentStateMapper = mock(AgentStateMapper.class);
    private final AgentArtifactMapper artifactMapper = mock(AgentArtifactMapper.class);
    private final WorkflowReviewMapper reviewMapper = mock(WorkflowReviewMapper.class);
    private final FileStorageService fileStorageService = mock(FileStorageService.class);
    private final AdminWorkflowCleanupService service = new AdminWorkflowCleanupService(
            workflowMapper,
            agentStateMapper,
            artifactMapper,
            reviewMapper,
            new IdempotencyExecutor(180),
            new ObjectMapper().findAndRegisterModules(),
            fileStorageService);

    @Test
    void deletesStableWorkflowWithOptionalReasonAndCleansReportFiles() {
        WorkflowState workflow = workflow(WorkflowStatus.COMPLETED, 7L);
        AgentArtifact artifact = new AgentArtifact();
        artifact.setArtifactId("ART-1");
        artifact.setResult("""
                {"files":[{"path":"storage/reports/WF-1/ART-1/report.pdf"}]}
                """);

        when(workflowMapper.selectById("WF-1")).thenReturn(workflow);
        when(artifactMapper.selectList(any())).thenReturn(List.of(artifact));
        when(reviewMapper.delete(any())).thenReturn(1);
        when(artifactMapper.delete(any())).thenReturn(1);
        when(agentStateMapper.delete(any())).thenReturn(1);
        when(workflowMapper.delete(any())).thenReturn(1);

        var response = service.delete(
                principal(), "WF-1", "delete-001", new AdminWorkflowDeleteRequest(null, 7L));

        assertThat(response.workflowId()).isEqualTo("WF-1");
        assertThat(response.deleted()).isTrue();
        assertThat(response.deletedAgentStates()).isEqualTo(1);
        assertThat(response.deletedArtifacts()).isEqualTo(1);
        assertThat(response.deletedReviews()).isEqualTo(1);
        assertThat(response.fileCleanup()).isEqualTo("BEST_EFFORT");
        verify(fileStorageService).deleteQuietly("storage/reports/WF-1/ART-1/report.pdf");
    }

    @Test
    void returnsCachedResultForRepeatedIdempotencyKey() {
        when(workflowMapper.selectById("WF-1"))
                .thenReturn(workflow(WorkflowStatus.CANCELED, 3L));
        when(artifactMapper.selectList(any())).thenReturn(List.of());
        when(reviewMapper.delete(any())).thenReturn(0);
        when(artifactMapper.delete(any())).thenReturn(0);
        when(agentStateMapper.delete(any())).thenReturn(0);
        when(workflowMapper.delete(any())).thenReturn(1);
        AdminWorkflowDeleteRequest request = new AdminWorkflowDeleteRequest("", 3L);

        var first = service.delete(principal(), "WF-1", "delete-002", request);
        var repeated = service.delete(principal(), "WF-1", "delete-002", request);

        assertThat(repeated).isEqualTo(first);
        verify(workflowMapper, times(1)).selectById("WF-1");
    }

    @Test
    void deletesRunningWorkflowWithoutStatusRestriction() {
        when(workflowMapper.selectById("WF-1"))
                .thenReturn(workflow(WorkflowStatus.RUNNING, 2L));
        when(artifactMapper.selectList(any())).thenReturn(List.of());
        when(reviewMapper.delete(any())).thenReturn(0);
        when(artifactMapper.delete(any())).thenReturn(0);
        when(agentStateMapper.delete(any())).thenReturn(1);
        when(workflowMapper.delete(any())).thenReturn(1);

        var response = service.delete(
                principal(), "WF-1", "delete-003", new AdminWorkflowDeleteRequest(null, 2L));

        assertThat(response.deleted()).isTrue();
        assertThat(response.deletedAgentStates()).isEqualTo(1);
        verify(reviewMapper).delete(any());
        verify(artifactMapper).delete(any());
        verify(agentStateMapper).delete(any());
        verify(workflowMapper).delete(any());
    }

    @Test
    void deletesGeneratingOutputWorkflowWithoutStatusRestriction() {
        when(workflowMapper.selectById("WF-1"))
                .thenReturn(workflow(WorkflowStatus.GENERATING_OUTPUT, 4L));
        when(artifactMapper.selectList(any())).thenReturn(List.of());
        when(reviewMapper.delete(any())).thenReturn(0);
        when(artifactMapper.delete(any())).thenReturn(0);
        when(agentStateMapper.delete(any())).thenReturn(0);
        when(workflowMapper.delete(any())).thenReturn(1);

        var response = service.delete(
                principal(), "WF-1", "delete-004", new AdminWorkflowDeleteRequest("展示数据清理", 4L));

        assertThat(response.deleted()).isTrue();
    }

    @Test
    void rejectsStaleExpectedVersion() {
        when(workflowMapper.selectById("WF-1"))
                .thenReturn(workflow(WorkflowStatus.FAILED, 9L));

        assertThatThrownBy(() -> service.delete(
                principal(), "WF-1", "delete-006", new AdminWorkflowDeleteRequest("cleanup", 8L)))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> {
                    BusinessException exception = (BusinessException) error;
                    assertThat(exception.getStatus().value()).isEqualTo(409);
                    assertThat(exception.getCode()).isEqualTo(ErrorCode.STATE_CONFLICT);
                });

        verify(artifactMapper, never()).selectList(any());
    }

    private WorkflowState workflow(WorkflowStatus status, Long version) {
        WorkflowState workflow = new WorkflowState();
        workflow.setWorkflowId("WF-1");
        workflow.setWorkflowStatus(status);
        workflow.setVersion(version);
        return workflow;
    }

    private CurrentUserPrincipal principal() {
        return new CurrentUserPrincipal("ADMIN-001", "System Admin", RoleName.SYSTEM_ADMIN);
    }
}
