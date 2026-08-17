package com.privatebank.business.service.workflow;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.business.common.idempotency.IdempotencyExecutor;
import com.privatebank.business.common.exception.BusinessException;
import com.privatebank.business.common.exception.ErrorCode;
import com.privatebank.business.dto.customer.CustomerSummaryResponse;
import com.privatebank.business.dto.workflow.AvailableImportBatchResponse;
import com.privatebank.business.dto.workflow.CustomerInsightAnalysisResponse;
import com.privatebank.business.dto.workflow.CustomerInsightRetryRequest;
import com.privatebank.business.dto.workflow.CustomerManagerWorkflowResponse;
import com.privatebank.business.dto.workflow.CreateWorkflowRequest;
import com.privatebank.business.dto.workflow.WorkflowInputRequest;
import com.privatebank.business.entity.workflow.AgentArtifact;
import com.privatebank.business.entity.workflow.AgentState;
import com.privatebank.business.entity.workflow.WorkflowState;
import com.privatebank.business.enums.auth.RoleName;
import com.privatebank.business.enums.workflow.AgentStatus;
import com.privatebank.business.enums.workflow.AgentType;
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
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowServiceKycReviewTest {

    @Test
    void listsOnlyCompletedBatchesThatContainSelectedCustomerData() {
        Fixture fixture = fixture();
        when(fixture.customerDataMapper.findSummary(100L)).thenReturn(customer());
        AvailableImportBatchResponse batch = new AvailableImportBatchResponse(
                4L, "batch-4", LocalDate.now().atStartOfDay(), 120);
        when(fixture.importBatchMapper.countAvailableForCustomer(100L)).thenReturn(1L);
        when(fixture.importBatchMapper.findAvailableForCustomer(100L, 0, 50)).thenReturn(List.of(batch));

        var response = fixture.service.availableImportBatches(principal(), 100L, 1, 50);

        assertThat(response.items()).containsExactly(batch);
        assertThat(response.total()).isEqualTo(1);
    }

    @Test
    void rejectsCreationWhenSelectedBatchIsNotAvailableForCustomer() {
        Fixture fixture = fixture();
        when(fixture.customerDataMapper.findSummary(100L)).thenReturn(customer());
        when(fixture.importBatchMapper.isCompletedAndAvailableForCustomer(4L, 100L)).thenReturn(false);

        assertThatThrownBy(() -> fixture.service.create(principal(), "create-key",
                new CreateWorkflowRequest(100L, 4L, LocalDate.now(), "CFS-3P6-V1", null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> {
                    BusinessException exception = (BusinessException) error;
                    assertThat(exception.getStatus().value()).isEqualTo(422);
                    assertThat(exception.getCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
                });

        verify(fixture.workflowMapper, never()).insert(any(WorkflowState.class));
    }

    @Test
    void listsOnlyCurrentManagersWorkflowsWithinTheirActiveCustomerScope() {
        Fixture fixture = fixture();
        CustomerManagerWorkflowResponse workflow = new CustomerManagerWorkflowResponse(
                "WF-1", 100L, "Test Customer", WorkflowStatus.RUNNING,
                "CFS-3P6-V1", LocalDate.now(), LocalDate.now().atStartOfDay());
        when(fixture.workflowMapper.countForCustomerManager("U-1", null, WorkflowStatus.RUNNING)).thenReturn(1L);
        when(fixture.workflowMapper.findForCustomerManager("U-1", null, WorkflowStatus.RUNNING, 0, 20))
                .thenReturn(List.of(workflow));

        var response = fixture.service.customerManagerWorkflows(
                principal(), null, WorkflowStatus.RUNNING, 1, 20);

        assertThat(response.items()).containsExactly(workflow);
        assertThat(response.total()).isEqualTo(1);
    }

    @Test
    void returnsLatestCustomerInsightAnalysis() {
        Fixture fixture = fixture();
        WorkflowState workflow = workflow();
        AgentArtifact artifact = kycArtifact("ART-2", 2);
        artifact.setExecutionId("EXE-2");
        artifact.setCreateTime(LocalDateTime.of(2026, 8, 14, 15, 30));
        artifact.setResult("""
                {"contractVersion":"kyc-result.v2","aliasMappings":{
                  "P-1":"张三","E-1":"某某科技有限公司"
                },"analysis":{
                  "riskLevel":"MEDIUM","summary":"客户P-1关联E-1","findings":[{
                    "dimension":"PERSON","riskLevel":"MEDIUM","finding":"P-1存在风险","evidenceRefs":["SRC-1"]
                  }],"riskAlerts":["E-1风险提示"],"recommendedActions":["联系P-1"],"dataGaps":["缺少E-1信息"],
                  "graphAssessment":{"contribution":"CONFIRMATORY","summary":"P-1与E-1图谱印证","evidenceRefs":["SRC-2"]}
                }}""");
        AgentState state = agentState(AgentType.CUSTOMER_INSIGHT, AgentStatus.SUCCESS);
        when(fixture.workflowMapper.selectById("WF-1")).thenReturn(workflow);
        when(fixture.artifactMapper.selectOne(anyArtifactQuery())).thenReturn(artifact);
        when(fixture.agentStateMapper.selectOne(anyAgentStateQuery())).thenReturn(state);

        CustomerInsightAnalysisResponse response = fixture.service.customerInsight(principal(), "WF-1");

        assertThat(response.artifactId()).isEqualTo("ART-2");
        assertThat(response.version()).isEqualTo(2);
        assertThat(response.actionable()).isTrue();
        assertThat(response.analysis().riskLevel()).isEqualTo("MEDIUM");
        assertThat(response.analysis().summary()).isEqualTo("客户张三关联某某科技有限公司");
        assertThat(response.analysis().findings().getFirst().finding()).isEqualTo("张三存在风险");
        assertThat(response.analysis().riskAlerts()).containsExactly("某某科技有限公司风险提示");
        assertThat(response.analysis().recommendedActions()).containsExactly("联系张三");
        assertThat(response.analysis().dataGaps()).containsExactly("缺少某某科技有限公司信息");
        assertThat(response.analysis().graphAssessment().summary())
                .isEqualTo("张三与某某科技有限公司图谱印证");
        assertThat(response.analysis().findings()).singleElement()
                .extracting(CustomerInsightAnalysisResponse.Finding::evidenceRefs)
                .isEqualTo(List.of("SRC-1"));
        assertThat(artifact.getResult()).contains("P-1存在风险", "E-1风险提示");
    }

    @Test
    void regeneratesKycWithRuntimeOnlyManagerSupplement() {
        Fixture fixture = fixture();
        WorkflowState workflow = workflow();
        AgentArtifact current = kycArtifact("ART-1", 1);
        when(fixture.workflowMapper.selectById("WF-1")).thenReturn(workflow);
        when(fixture.artifactMapper.selectById("ART-1")).thenReturn(current);
        when(fixture.artifactMapper.selectOne(anyArtifactQuery())).thenReturn(current);
        AgentState kycState = agentState(AgentType.CUSTOMER_INSIGHT, AgentStatus.SUCCESS);
        when(fixture.agentStateMapper.selectOne(anyAgentStateQuery())).thenReturn(kycState);
        when(fixture.agentStateMapper.updateById(any(AgentState.class))).thenReturn(1);
        when(fixture.workflowMapper.updateById(any(WorkflowState.class))).thenReturn(1);
        when(fixture.agentStateMapper.selectList(anyAgentStateQuery())).thenReturn(List.of(kycState));

        var response = fixture.service.provideInput(principal(), "WF-1", "supplement-key",
                new WorkflowInputRequest(WorkflowInputRequest.Action.SUPPLEMENT, "ART-1",
                        "请补充客户近期流动性安排", List.of("近期流动性安排")));

        assertThat(response.workflowStatus()).isEqualTo(WorkflowStatus.RUNNING);
        assertThat(workflow.getWorkflowStatus()).isEqualTo(WorkflowStatus.RUNNING);
        assertThat(kycState.getAgentStatus()).isEqualTo(AgentStatus.READY);
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(fixture.eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isEqualTo(new KycRegenerationRequestedEvent(
                "WF-1", "请补充客户近期流动性安排", List.of("近期流动性安排")));
        verify(fixture.artifactMapper, never()).insert(any(AgentArtifact.class));
        verify(fixture.eventPublisher, never()).publishEvent(any(DownstreamAgentsReadyEvent.class));
    }

    @Test
    void retriesCustomerInsightAfterModelCallFailure() {
        Fixture fixture = fixture();
        WorkflowState workflow = workflow();
        workflow.setWorkflowStatus(WorkflowStatus.FAILED);
        workflow.setErrorCode("KYC_MODEL_CALL_FAILED");
        workflow.setErrorMessage("KYC 模型调用失败，请稍后重试");
        workflow.setFinishTime(LocalDateTime.now());
        AgentState kycState = agentState(AgentType.CUSTOMER_INSIGHT, AgentStatus.FAILED);
        kycState.setExecutionId("EXE-FAILED");
        kycState.setErrorCode("KYC_MODEL_CALL_FAILED");
        kycState.setErrorMessage("KYC 模型调用失败，请稍后重试");
        kycState.setStartTime(LocalDateTime.now().minusMinutes(1));
        kycState.setFinishTime(LocalDateTime.now());
        when(fixture.workflowMapper.selectById("WF-1")).thenReturn(workflow);
        when(fixture.agentStateMapper.selectOne(anyAgentStateQuery())).thenReturn(kycState);
        when(fixture.agentStateMapper.updateById(any(AgentState.class))).thenReturn(1);
        when(fixture.workflowMapper.updateById(any(WorkflowState.class))).thenReturn(1);
        when(fixture.agentStateMapper.selectList(anyAgentStateQuery())).thenReturn(List.of(kycState));

        var response = fixture.service.retryCustomerInsight(principal(), "WF-1", "retry-key",
                new CustomerInsightRetryRequest("EXE-FAILED"));

        assertThat(response.workflowStatus()).isEqualTo(WorkflowStatus.RUNNING);
        assertThat(workflow.getWorkflowStatus()).isEqualTo(WorkflowStatus.RUNNING);
        assertThat(workflow.getErrorCode()).isNull();
        assertThat(workflow.getErrorMessage()).isNull();
        assertThat(workflow.getFinishTime()).isNull();
        assertThat(kycState.getAgentStatus()).isEqualTo(AgentStatus.READY);
        assertThat(kycState.getExecutionId()).startsWith("EXE-").isNotEqualTo("EXE-FAILED");
        assertThat(kycState.getErrorCode()).isNull();
        assertThat(kycState.getErrorMessage()).isNull();
        assertThat(kycState.getStartTime()).isNull();
        assertThat(kycState.getFinishTime()).isNull();
        verify(fixture.eventPublisher).publishEvent(
                new KycRegenerationRequestedEvent("WF-1", null, List.of()));
    }

    @Test
    void rejectsCustomerInsightRetryForNonModelFailure() {
        Fixture fixture = fixture();
        WorkflowState workflow = workflow();
        workflow.setWorkflowStatus(WorkflowStatus.FAILED);
        workflow.setErrorCode("KYC_OUTPUT_CONTRACT_INVALID");
        AgentState kycState = agentState(AgentType.CUSTOMER_INSIGHT, AgentStatus.FAILED);
        kycState.setExecutionId("EXE-FAILED");
        kycState.setErrorCode("KYC_OUTPUT_CONTRACT_INVALID");
        when(fixture.workflowMapper.selectById("WF-1")).thenReturn(workflow);
        when(fixture.agentStateMapper.selectOne(anyAgentStateQuery())).thenReturn(kycState);

        assertThatThrownBy(() -> fixture.service.retryCustomerInsight(principal(), "WF-1", "retry-key",
                new CustomerInsightRetryRequest("EXE-FAILED")))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> {
                    BusinessException exception = (BusinessException) error;
                    assertThat(exception.getStatus().value()).isEqualTo(409);
                    assertThat(exception.getCode()).isEqualTo(ErrorCode.STATE_CONFLICT);
                });

        verify(fixture.agentStateMapper, never()).updateById(any(AgentState.class));
        verify(fixture.workflowMapper, never()).updateById(any(WorkflowState.class));
        verify(fixture.eventPublisher, never()).publishEvent(any(KycRegenerationRequestedEvent.class));
    }

    @Test
    void rejectsCustomerInsightRetryForStaleFailedExecution() {
        Fixture fixture = fixture();
        WorkflowState workflow = workflow();
        workflow.setWorkflowStatus(WorkflowStatus.FAILED);
        workflow.setErrorCode("KYC_MODEL_CALL_FAILED");
        AgentState kycState = agentState(AgentType.CUSTOMER_INSIGHT, AgentStatus.FAILED);
        kycState.setExecutionId("EXE-CURRENT");
        kycState.setErrorCode("KYC_MODEL_CALL_FAILED");
        when(fixture.workflowMapper.selectById("WF-1")).thenReturn(workflow);
        when(fixture.agentStateMapper.selectOne(anyAgentStateQuery())).thenReturn(kycState);

        assertThatThrownBy(() -> fixture.service.retryCustomerInsight(principal(), "WF-1", "retry-key",
                new CustomerInsightRetryRequest("EXE-STALE")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已变化");

        verify(fixture.agentStateMapper, never()).updateById(any(AgentState.class));
        verify(fixture.workflowMapper, never()).updateById(any(WorkflowState.class));
    }

    @Test
    void regeneratesKycAfterSupplementedCustomerDataIsAvailable() {
        Fixture fixture = fixture();
        WorkflowState workflow = workflow();
        AgentArtifact current = kycArtifact("ART-1", 1);
        AgentState kycState = agentState(AgentType.CUSTOMER_INSIGHT, AgentStatus.SUCCESS);
        when(fixture.workflowMapper.selectById("WF-1")).thenReturn(workflow);
        when(fixture.artifactMapper.selectById("ART-1")).thenReturn(current);
        when(fixture.artifactMapper.selectOne(anyArtifactQuery())).thenReturn(current);
        when(fixture.agentStateMapper.selectOne(anyAgentStateQuery())).thenReturn(kycState);
        when(fixture.agentStateMapper.updateById(any(AgentState.class))).thenReturn(1);
        when(fixture.workflowMapper.updateById(any(WorkflowState.class))).thenReturn(1);
        when(fixture.agentStateMapper.selectList(anyAgentStateQuery())).thenReturn(List.of(kycState));

        var response = fixture.service.provideInput(principal(), "WF-1", "regenerate-key",
                new WorkflowInputRequest(WorkflowInputRequest.Action.REGENERATE, "ART-1",
                        "已补充近期流动性安排", List.of("近期流动性安排")));

        assertThat(response.workflowStatus()).isEqualTo(WorkflowStatus.RUNNING);
        assertThat(kycState.getAgentStatus()).isEqualTo(AgentStatus.READY);
        assertThat(workflow.getWorkflowStatus()).isEqualTo(WorkflowStatus.RUNNING);
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(fixture.eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isEqualTo(new KycRegenerationRequestedEvent(
                "WF-1", "已补充近期流动性安排", List.of("近期流动性安排")));
        verify(fixture.artifactMapper, never()).insert(any(AgentArtifact.class));
    }

    @Test
    void approvesKycAndReleasesTheTwoDownstreamAgents() {
        Fixture fixture = fixture();
        WorkflowState workflow = workflow();
        AgentArtifact current = kycArtifact("ART-2", 2);
        AgentState marketState = agentState(AgentType.MARKET_INSIGHT, AgentStatus.PENDING);
        AgentState productState = agentState(AgentType.PRODUCT_EXPERT, AgentStatus.PENDING);
        when(fixture.workflowMapper.selectById("WF-1")).thenReturn(workflow);
        when(fixture.artifactMapper.selectById("ART-2")).thenReturn(current);
        when(fixture.artifactMapper.selectOne(anyArtifactQuery())).thenReturn(current);
        when(fixture.agentStateMapper.selectOne(anyAgentStateQuery())).thenReturn(marketState, productState);
        when(fixture.agentStateMapper.updateById(any(AgentState.class))).thenReturn(1);
        when(fixture.workflowMapper.updateById(any(WorkflowState.class))).thenReturn(1);
        when(fixture.agentStateMapper.selectList(anyAgentStateQuery())).thenReturn(List.of(marketState, productState));

        var response = fixture.service.provideInput(principal(), "WF-1", "approve-key",
                new WorkflowInputRequest(WorkflowInputRequest.Action.CONTINUE, "ART-2", null, List.of()));

        assertThat(response.workflowStatus()).isEqualTo(WorkflowStatus.RUNNING);
        assertThat(marketState.getAgentStatus()).isEqualTo(AgentStatus.READY);
        assertThat(productState.getAgentStatus()).isEqualTo(AgentStatus.READY);
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(fixture.eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isEqualTo(new DownstreamAgentsReadyEvent(
                "WF-1", "ART-2", List.of(AgentType.MARKET_INSIGHT, AgentType.PRODUCT_EXPERT)));
    }

    @Test
    void rejectsEmptySupplementBeforeItCanStartARegeneration() {
        Fixture fixture = fixture();
        WorkflowState workflow = workflow();
        AgentArtifact current = kycArtifact("ART-1", 1);
        when(fixture.workflowMapper.selectById("WF-1")).thenReturn(workflow);
        when(fixture.artifactMapper.selectById("ART-1")).thenReturn(current);
        when(fixture.artifactMapper.selectOne(anyArtifactQuery())).thenReturn(current);

        assertThatThrownBy(() -> fixture.service.provideInput(principal(), "WF-1", "empty-supplement-key",
                new WorkflowInputRequest(WorkflowInputRequest.Action.SUPPLEMENT, "ART-1", " ", List.of())))
                .hasMessageContaining("必须提供补充说明");

        verify(fixture.agentStateMapper, never()).updateById(any(AgentState.class));
        verify(fixture.eventPublisher, never()).publishEvent(any(KycRegenerationRequestedEvent.class));
    }

    @SuppressWarnings("unchecked")
    private static Wrapper<AgentArtifact> anyArtifactQuery() {
        return any(Wrapper.class);
    }

    @SuppressWarnings("unchecked")
    private static Wrapper<AgentState> anyAgentStateQuery() {
        return any(Wrapper.class);
    }

    private Fixture fixture() {
        WorkflowStateMapper workflowMapper = mock(WorkflowStateMapper.class);
        AgentStateMapper agentStateMapper = mock(AgentStateMapper.class);
        AgentArtifactMapper artifactMapper = mock(AgentArtifactMapper.class);
        CurrentUserService currentUserService = mock(CurrentUserService.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        CustomerDataMapper customerDataMapper = mock(CustomerDataMapper.class);
        ImportBatchMapper importBatchMapper = mock(ImportBatchMapper.class);
        WorkflowService service = new WorkflowService(
                workflowMapper,
                agentStateMapper,
                artifactMapper,
                mock(WorkflowReviewMapper.class),
                customerDataMapper,
                importBatchMapper,
                currentUserService,
                new IdempotencyExecutor(180),
                mock(WorkflowEventHub.class),
                eventPublisher,
                new ObjectMapper().findAndRegisterModules(),
                mock(FileStorageService.class),
                new CustomerInsightAliasRestorer());
        return new Fixture(service, workflowMapper, agentStateMapper, artifactMapper, eventPublisher,
                customerDataMapper, importBatchMapper);
    }

    private CustomerSummaryResponse customer() {
        return new CustomerSummaryResponse(100L, "Test Customer", "Test Customer", "PERSON", "VERIFIED", "R2");
    }

    private CurrentUserPrincipal principal() {
        return new CurrentUserPrincipal("U-1", "客户经理", RoleName.CUSTOMER_MANAGER);
    }

    private WorkflowState workflow() {
        WorkflowState workflow = new WorkflowState();
        workflow.setWorkflowId("WF-1");
        workflow.setPersonId(100L);
        workflow.setWorkflowStatus(WorkflowStatus.WAITING_INPUT);
        workflow.setVersion(0L);
        return workflow;
    }

    private AgentArtifact kycArtifact(String artifactId, int version) {
        AgentArtifact artifact = new AgentArtifact();
        artifact.setArtifactId(artifactId);
        artifact.setWorkflowId("WF-1");
        artifact.setAgentType(AgentType.CUSTOMER_INSIGHT);
        artifact.setVersion(version);
        return artifact;
    }

    private AgentState agentState(AgentType type, AgentStatus status) {
        AgentState state = new AgentState();
        state.setAgentStateId("AS-" + type.name());
        state.setWorkflowId("WF-1");
        state.setAgentType(type);
        state.setAgentStatus(status);
        state.setVersion(0L);
        state.setRetryCount(0);
        return state;
    }

    private record Fixture(
            WorkflowService service,
            WorkflowStateMapper workflowMapper,
            AgentStateMapper agentStateMapper,
            AgentArtifactMapper artifactMapper,
            ApplicationEventPublisher eventPublisher,
            CustomerDataMapper customerDataMapper,
            ImportBatchMapper importBatchMapper) {
    }
}
