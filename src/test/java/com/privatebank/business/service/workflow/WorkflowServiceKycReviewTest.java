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
import com.privatebank.business.dto.workflow.CfsReportWorkflowRow;
import com.privatebank.business.dto.workflow.CustomerManagerWorkflowResponse;
import com.privatebank.business.dto.workflow.CreateWorkflowRequest;
import com.privatebank.business.dto.workflow.OutputRetryRequest;
import com.privatebank.business.dto.workflow.WorkflowInputRequest;
import com.privatebank.business.dto.workflow.ReviewRequest;
import com.privatebank.business.entity.workflow.AgentArtifact;
import com.privatebank.business.entity.workflow.AgentState;
import com.privatebank.business.entity.workflow.WorkflowState;
import com.privatebank.business.entity.workflow.WorkflowReview;
import com.privatebank.business.dto.workflow.KycQaItem;
import com.privatebank.business.enums.auth.RoleName;
import com.privatebank.business.enums.workflow.AgentStatus;
import com.privatebank.business.enums.workflow.CfsReportStatus;
import com.privatebank.business.enums.workflow.AgentType;
import com.privatebank.business.enums.workflow.WorkflowStatus;
import com.privatebank.business.enums.workflow.ReviewStatus;
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
import java.util.Map;

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
    void listsReportsAfterComplianceAndExposesActionsByLifecycleState() {
        Fixture fixture = fixture();
        LocalDateTime updatedAt = LocalDateTime.of(2026, 8, 21, 20, 30);
        CfsReportWorkflowRow pending = new CfsReportWorkflowRow(
                "WF-PENDING", 100L, "Pending Customer", WorkflowStatus.WAITING_INPUT,
                "CFS-3P6-V1", LocalDate.of(2026, 8, 20), null, null, updatedAt);
        CfsReportWorkflowRow ready = new CfsReportWorkflowRow(
                "WF-READY", 101L, "Ready Customer", WorkflowStatus.COMPLETED,
                "CFS-3P6-V1", LocalDate.of(2026, 8, 19), null, null, updatedAt);

        AgentArtifact pendingCfs = artifact("ART-CFS-PENDING", AgentType.SOLUTION_DESIGN);
        pendingCfs.setWorkflowId("WF-PENDING");
        pendingCfs.setResult("{\"customerId\":\"P-100\",\"cfsVersion\":2,\"cfsStructure\":{}}");
        AgentArtifact pendingCompliance = artifact("ART-COMP-PENDING", AgentType.COMPLIANCE_CHECK);
        pendingCompliance.setWorkflowId("WF-PENDING");
        pendingCompliance.setComplianceResult("REVIEW_REQUIRED");
        pendingCompliance.setResult("{\"cfsArtifactRef\":\"ART-CFS-PENDING\"}");

        AgentArtifact readyCfs = artifact("ART-CFS-READY", AgentType.SOLUTION_DESIGN);
        readyCfs.setWorkflowId("WF-READY");
        readyCfs.setResult("""
                {"customerId":"P-101","cfsVersion":3,"cfsStructure":{},
                 "reportExportedAt":"2026-08-21T20:31:00+08:00",
                 "files":[{"fileId":"FILE-PDF","format":"PDF","fileName":"report.pdf",
                   "contentType":"application/pdf","sizeBytes":128,
                   "path":"reports/internal/report.pdf","generatedAt":"2026-08-21T20:31:00+08:00"}]}
                """);
        AgentArtifact readyCompliance = artifact("ART-COMP-READY", AgentType.COMPLIANCE_CHECK);
        readyCompliance.setWorkflowId("WF-READY");
        readyCompliance.setComplianceResult("PASS");
        readyCompliance.setResult("{\"cfsArtifactRef\":\"ART-CFS-READY\"}");

        when(fixture.workflowMapper.countForReportCenter("U-1", null, null)).thenReturn(2L);
        when(fixture.workflowMapper.findForReportCenter("U-1", null, null, 0, 20))
                .thenReturn(List.of(pending, ready));
        when(fixture.artifactMapper.selectOne(anyArtifactQuery()))
                .thenReturn(pendingCfs, pendingCompliance, readyCfs, readyCompliance);

        var response = fixture.service.reportCenter(principal(), null, null, 1, 20);

        assertThat(response.total()).isEqualTo(2);
        assertThat(response.items().get(0).reportStatus()).isEqualTo(CfsReportStatus.PENDING_REVIEW);
        assertThat(response.items().get(0).canPreview()).isTrue();
        assertThat(response.items().get(0).canReview()).isTrue();
        assertThat(response.items().get(0).canExport()).isFalse();
        assertThat(response.items().get(1).reportStatus()).isEqualTo(CfsReportStatus.READY);
        assertThat(response.items().get(1).canReview()).isFalse();
        assertThat(response.items().get(1).canExport()).isTrue();
        assertThat(response.items().get(1).files()).singleElement().satisfies(file -> {
            assertThat(file.fileId()).isEqualTo("FILE-PDF");
            assertThat(file.format()).isEqualTo("PDF");
            assertThat(file.sizeBytes()).isEqualTo(128L);
        });
    }

    @Test
    void previewsCompliantCfsBeforeHumanApproval() {
        Fixture fixture = fixture();
        WorkflowState workflow = workflow();
        workflow.setWorkflowStatus(WorkflowStatus.WAITING_INPUT);
        AgentArtifact cfs = artifact("ART-CFS", AgentType.SOLUTION_DESIGN);
        cfs.setCreateTime(LocalDateTime.of(2026, 8, 21, 20, 0));
        cfs.setResult("""
                {"customerId":"P-100","cfsVersion":4,
                 "cfsStructure":{"chapter1CustomerInfo":"客户概况"},
                 "marketingStrategy":"营销策略"}
                """);
        AgentArtifact compliance = artifact("ART-COMPLIANCE", AgentType.COMPLIANCE_CHECK);
        compliance.setComplianceResult("REVIEW_REQUIRED");
        compliance.setCreateTime(LocalDateTime.of(2026, 8, 21, 20, 5));
        compliance.setResult("{\"cfsArtifactRef\":\"ART-CFS\"}");
        when(fixture.workflowMapper.selectById("WF-1")).thenReturn(workflow);
        when(fixture.artifactMapper.selectOne(anyArtifactQuery())).thenReturn(cfs, compliance);
        when(fixture.customerDataMapper.findSummary(100L)).thenReturn(customer());

        var response = fixture.service.reportPreview(principal(), "WF-1");

        assertThat(response.reportStatus()).isEqualTo(CfsReportStatus.PENDING_REVIEW);
        assertThat(response.canReview()).isTrue();
        assertThat(response.canExport()).isFalse();
        assertThat(response.cfsArtifactId()).isEqualTo("ART-CFS");
        assertThat(response.complianceArtifactId()).isEqualTo("ART-COMPLIANCE");
        assertThat(response.content().path("cfsStructure").path("chapter1CustomerInfo").asText())
                .isEqualTo("客户概况");
        assertThat(response.content().has("files")).isFalse();
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
    void regeneratesKycWithAnswersAndPreservesQaHistory() {
        Fixture fixture = fixture();
        WorkflowState workflow = workflow();
        AgentArtifact current = kycArtifact("ART-1", 1);
        current.setResult("""
                {"contractVersion":"kyc-result.v2","analysis":{
                  "riskLevel":"MEDIUM","summary":"已有分析","findings":[],"riskAlerts":[],
                  "recommendedActions":[],"dataGaps":["缺少流动性安排"],
                  "followUpQuestions":[
                    {"id":"Q1","question":"P-1近期是否有流动性安排？"},
                    {"id":"Q2","question":"P-1是否有跨境配置需求？"}
                  ],
                  "graphAssessment":{"contribution":"NOT_AVAILABLE","summary":"无图谱","evidenceRefs":[]}
                },"qaHistory":[
                  {"questionId":"Q1","question":"P-1近期是否有流动性安排？","answer":"旧回答"}
                ]}
                """);
        AgentState kycState = agentState(AgentType.CUSTOMER_INSIGHT, AgentStatus.SUCCESS);
        when(fixture.workflowMapper.selectById("WF-1")).thenReturn(workflow);
        when(fixture.artifactMapper.selectById("ART-1")).thenReturn(current);
        when(fixture.artifactMapper.selectOne(anyArtifactQuery())).thenReturn(current);
        when(fixture.agentStateMapper.selectOne(anyAgentStateQuery())).thenReturn(kycState);
        when(fixture.agentStateMapper.updateById(any(AgentState.class))).thenReturn(1);
        when(fixture.workflowMapper.updateById(any(WorkflowState.class))).thenReturn(1);
        when(fixture.agentStateMapper.selectList(anyAgentStateQuery())).thenReturn(List.of(kycState));

        var response = fixture.service.provideInput(principal(), "WF-1", "answer-key",
                new WorkflowInputRequest(WorkflowInputRequest.Action.SUPPLEMENT, "ART-1",
                        null, List.of(),
                        List.of(
                                new WorkflowInputRequest.Answer("Q1", "新回答：近期有流动性安排"),
                                new WorkflowInputRequest.Answer("Q2", "没有跨境配置需求"))));

        assertThat(response.workflowStatus()).isEqualTo(WorkflowStatus.RUNNING);
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(fixture.eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isEqualTo(new KycRegenerationRequestedEvent(
                "WF-1", null, List.of(),
                List.of(
                        new KycQaItem("Q1", "P-1近期是否有流动性安排？", "新回答：近期有流动性安排"),
                        new KycQaItem("Q2", "P-1是否有跨境配置需求？", "没有跨境配置需求"))));
        verify(fixture.artifactMapper, never()).insert(any(AgentArtifact.class));
    }

    @Test
    void rejectsAnswerForUnknownQuestionId() {
        Fixture fixture = fixture();
        WorkflowState workflow = workflow();
        AgentArtifact current = kycArtifact("ART-1", 1);
        current.setResult("""
                {"contractVersion":"kyc-result.v2","analysis":{
                  "riskLevel":"MEDIUM","summary":"已有分析","findings":[],"riskAlerts":[],
                  "recommendedActions":[],"dataGaps":[],
                  "followUpQuestions":[{"id":"Q1","question":"P-1近期是否有流动性安排？"}],
                  "graphAssessment":{"contribution":"NOT_AVAILABLE","summary":"无图谱","evidenceRefs":[]}
                }}
                """);
        when(fixture.workflowMapper.selectById("WF-1")).thenReturn(workflow);
        when(fixture.artifactMapper.selectById("ART-1")).thenReturn(current);
        when(fixture.artifactMapper.selectOne(anyArtifactQuery())).thenReturn(current);

        assertThatThrownBy(() -> fixture.service.provideInput(principal(), "WF-1", "bad-answer-key",
                new WorkflowInputRequest(WorkflowInputRequest.Action.SUPPLEMENT, "ART-1",
                        null, List.of(),
                        List.of(new WorkflowInputRequest.Answer("Q9", "不存在的问题")))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("问题ID不存在于当前客户洞察分析");

        verify(fixture.agentStateMapper, never()).updateById(any(AgentState.class));
        verify(fixture.eventPublisher, never()).publishEvent(any(KycRegenerationRequestedEvent.class));
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
                new CustomerInsightRetryRequest(
                        "EXE-FAILED", "重新关注流动性", List.of("客户确认近期存在流动性安排")));

        assertThat(response.workflowStatus()).isEqualTo(WorkflowStatus.RUNNING);
        assertThat(workflow.getWorkflowStatus()).isEqualTo(WorkflowStatus.RUNNING);
        assertThat(workflow.getErrorCode()).isNull();
        assertThat(workflow.getErrorMessage()).isNull();
        assertThat(workflow.getFinishTime()).isNull();
        assertThat(kycState.getAgentStatus()).isEqualTo(AgentStatus.READY);
        assertThat(kycState.getExecutionId()).isEqualTo("EXE-FAILED");
        assertThat(kycState.getErrorCode()).isNull();
        assertThat(kycState.getErrorMessage()).isNull();
        assertThat(kycState.getStartTime()).isNull();
        assertThat(kycState.getFinishTime()).isNull();
        verify(fixture.eventPublisher).publishEvent(
                new KycRegenerationRequestedEvent(
                        "WF-1", "重新关注流动性", List.of("客户确认近期存在流动性安排")));
    }

    @Test
    void retriesCustomerInsightAfterOutputContractFailure() {
        Fixture fixture = fixture();
        WorkflowState workflow = workflow();
        workflow.setWorkflowStatus(WorkflowStatus.FAILED);
        workflow.setErrorCode("KYC_OUTPUT_CONTRACT_INVALID");
        workflow.setErrorMessage("KYC 分析结果未通过证据、格式或脱敏校验");
        workflow.setFinishTime(LocalDateTime.now());
        AgentState kycState = agentState(AgentType.CUSTOMER_INSIGHT, AgentStatus.FAILED);
        kycState.setExecutionId("EXE-FAILED");
        kycState.setErrorCode("KYC_OUTPUT_CONTRACT_INVALID");
        kycState.setErrorMessage("KYC 分析结果未通过证据、格式或脱敏校验");
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
        assertThat(kycState.getExecutionId()).isEqualTo("EXE-FAILED");
        assertThat(kycState.getErrorCode()).isNull();
        assertThat(kycState.getErrorMessage()).isNull();
        assertThat(kycState.getStartTime()).isNull();
        assertThat(kycState.getFinishTime()).isNull();
        verify(fixture.eventPublisher).publishEvent(
                new KycRegenerationRequestedEvent("WF-1", null, List.of()));
    }

    @Test
    void rejectsCustomerInsightRetryForNonRetryableFailure() {
        Fixture fixture = fixture();
        WorkflowState workflow = workflow();
        workflow.setWorkflowStatus(WorkflowStatus.FAILED);
        workflow.setErrorCode("KYC_MASKED_INPUT_INVALID");
        AgentState kycState = agentState(AgentType.CUSTOMER_INSIGHT, AgentStatus.FAILED);
        kycState.setExecutionId("EXE-FAILED");
        kycState.setErrorCode("KYC_MASKED_INPUT_INVALID");
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
        workflow.setErrorCode("STALE_ERROR");
        workflow.setErrorMessage("stale error message");
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
        assertThat(workflow.getErrorCode()).isNull();
        assertThat(workflow.getErrorMessage()).isNull();
        assertThat(workflow.getStartTime()).isNotNull();
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(fixture.eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isEqualTo(new DownstreamAgentsReadyEvent(
                "WF-1", "ART-2", List.of(AgentType.MARKET_INSIGHT, AgentType.PRODUCT_EXPERT)));
    }

    @Test
    void approvesFinalReviewAndRequestsReportExport() {
        Fixture fixture = fixture();
        WorkflowState workflow = workflow();
        workflow.setWorkflowStatus(WorkflowStatus.WAITING_INPUT);
        AgentArtifact cfs = artifact("ART-CFS", AgentType.SOLUTION_DESIGN);
        AgentArtifact compliance = artifact("ART-COMPLIANCE", AgentType.COMPLIANCE_CHECK);
        compliance.setComplianceResult("REVIEW_REQUIRED");
        compliance.setResult("{\"cfsArtifactRef\":\"ART-CFS\"}");
        when(fixture.workflowMapper.selectById("WF-1")).thenReturn(workflow);
        when(fixture.artifactMapper.selectById("ART-CFS")).thenReturn(cfs);
        when(fixture.artifactMapper.selectById("ART-COMPLIANCE")).thenReturn(compliance);
        when(fixture.reviewMapper.selectOne(any())).thenReturn(null);
        when(fixture.reviewMapper.insert(any(WorkflowReview.class))).thenReturn(1);
        when(fixture.workflowMapper.updateById(workflow)).thenReturn(1);

        var response = fixture.service.review(principal(), "WF-1", "review-approve-key",
                new ReviewRequest("ART-CFS", "ART-COMPLIANCE", ReviewRequest.Decision.APPROVE, "approved"));

        assertThat(response.reviewStatus()).isEqualTo(ReviewStatus.APPROVED);
        assertThat(response.workflowStatus()).isEqualTo(WorkflowStatus.GENERATING_OUTPUT);
        assertThat(workflow.getWorkflowStatus()).isEqualTo(WorkflowStatus.GENERATING_OUTPUT);
        verify(fixture.eventPublisher).publishEvent(new CfsReportExportRequestedEvent(
                "WF-1", "ART-CFS", "ART-COMPLIANCE"));
    }

    @Test
    void retriesAStuckOrHistoricalOutputAndRequestsReportExport() {
        Fixture fixture = fixture();
        WorkflowState workflow = workflow();
        workflow.setWorkflowStatus(WorkflowStatus.COMPLETED);
        workflow.setFinishTime(LocalDateTime.now());
        AgentArtifact cfs = artifact("ART-CFS", AgentType.SOLUTION_DESIGN);
        AgentArtifact compliance = artifact("ART-COMPLIANCE", AgentType.COMPLIANCE_CHECK);
        compliance.setComplianceResult("REVIEW_REQUIRED");
        compliance.setResult("{\"cfsArtifactId\":\"ART-CFS\"}");
        when(fixture.workflowMapper.selectById("WF-1")).thenReturn(workflow);
        when(fixture.artifactMapper.selectOne(anyArtifactQuery())).thenReturn(cfs, compliance);
        when(fixture.workflowMapper.updateById(workflow)).thenReturn(1);

        var response = fixture.service.retryOutput(principal(), "WF-1", "output-retry-key",
                new OutputRetryRequest(List.of(OutputRetryRequest.Format.PDF)));

        assertThat(response.workflowStatus()).isEqualTo(WorkflowStatus.GENERATING_OUTPUT);
        assertThat(workflow.getWorkflowStatus()).isEqualTo(WorkflowStatus.GENERATING_OUTPUT);
        assertThat(workflow.getFinishTime()).isNull();
        verify(fixture.eventPublisher).publishEvent(new CfsReportExportRequestedEvent(
                "WF-1", "ART-CFS", "ART-COMPLIANCE"));
    }

    @Test
    void rejectsReviewAndReleasesCfsWithLatestInputArtifacts() {
        Fixture fixture = fixture();
        WorkflowState workflow = workflow();
        workflow.setWorkflowStatus(WorkflowStatus.WAITING_REVIEW);
        AgentArtifact cfs = artifact("ART-CFS", AgentType.SOLUTION_DESIGN);
        AgentArtifact compliance = artifact("ART-COMPLIANCE", AgentType.COMPLIANCE_CHECK);
        compliance.setComplianceResult("PASS");
        compliance.setResult("{\"cfsArtifactId\":\"ART-CFS\"}");
        AgentState cfsState = agentState(AgentType.SOLUTION_DESIGN, AgentStatus.SUCCESS);
        cfsState.setExecutionId("EXE-CFS-OLD");
        AgentArtifact kyc = artifact("ART-KYC", AgentType.CUSTOMER_INSIGHT);
        AgentArtifact market = artifact("ART-MARKET", AgentType.MARKET_INSIGHT);
        AgentArtifact product = artifact("ART-PRODUCT", AgentType.PRODUCT_EXPERT);
        when(fixture.workflowMapper.selectById("WF-1")).thenReturn(workflow);
        when(fixture.artifactMapper.selectById("ART-CFS")).thenReturn(cfs);
        when(fixture.artifactMapper.selectById("ART-COMPLIANCE")).thenReturn(compliance);
        when(fixture.reviewMapper.selectOne(any())).thenReturn(null);
        when(fixture.reviewMapper.insert(any(WorkflowReview.class))).thenReturn(1);
        when(fixture.agentStateMapper.selectOne(anyAgentStateQuery())).thenReturn(cfsState);
        when(fixture.agentStateMapper.updateById(any(AgentState.class))).thenReturn(1);
        when(fixture.workflowMapper.updateById(any(WorkflowState.class))).thenReturn(1);
        when(fixture.artifactMapper.selectOne(anyArtifactQuery())).thenReturn(kyc, market, product);

        var response = fixture.service.review(principal(), "WF-1", "review-reject-key",
                new ReviewRequest("ART-CFS", "ART-COMPLIANCE", ReviewRequest.Decision.REJECT, "请重新生成CFS"));

        assertThat(response.reviewStatus()).isEqualTo(ReviewStatus.REJECTED);
        assertThat(response.workflowStatus()).isEqualTo(WorkflowStatus.RUNNING);
        assertThat(workflow.getWorkflowStatus()).isEqualTo(WorkflowStatus.RUNNING);
        assertThat(cfsState.getAgentStatus()).isEqualTo(AgentStatus.READY);
        assertThat(cfsState.getExecutionId()).isEqualTo("EXE-CFS-OLD");
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(fixture.eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isEqualTo(new AgentDispatchRequestedEvent(
                "WF-1", AgentType.SOLUTION_DESIGN, Map.of(
                        "kycArtifactId", "ART-KYC",
                        "marketArtifactId", "ART-MARKET",
                        "kypArtifactId", "ART-PRODUCT")));
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

    @Test
    void rejectsDuplicateAnswersForSameQuestionId() {
        Fixture fixture = fixture();
        WorkflowState workflow = workflow();
        AgentArtifact current = kycArtifact("ART-1", 1);
        current.setResult("""
                {"contractVersion":"kyc-result.v2","analysis":{
                  "riskLevel":"MEDIUM","summary":"existing analysis","findings":[],"riskAlerts":[],
                  "recommendedActions":[],"dataGaps":[],
                  "followUpQuestions":[{"id":"Q1","question":"confirm liquidity arrangement"}],
                  "graphAssessment":{"contribution":"NOT_AVAILABLE","summary":"no graph","evidenceRefs":[]}
                }}
                """);
        when(fixture.workflowMapper.selectById("WF-1")).thenReturn(workflow);
        when(fixture.artifactMapper.selectById("ART-1")).thenReturn(current);
        when(fixture.artifactMapper.selectOne(anyArtifactQuery())).thenReturn(current);

        assertThatThrownBy(() -> fixture.service.provideInput(principal(), "WF-1", "duplicate-answer-key",
                new WorkflowInputRequest(WorkflowInputRequest.Action.SUPPLEMENT, "ART-1", null, List.of(),
                        List.of(
                                new WorkflowInputRequest.Answer("Q1", "answer-one"),
                                new WorkflowInputRequest.Answer("Q1", "answer-two")))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("同一个问题不能重复提交回答");

        verify(fixture.agentStateMapper, never()).updateById(any(AgentState.class));
        verify(fixture.eventPublisher, never()).publishEvent(any(KycRegenerationRequestedEvent.class));
    }

    @Test
    void concurrentRequestsWithSameIdempotencyKeyRegenerateKycOnce() throws Exception {
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

        WorkflowInputRequest request = new WorkflowInputRequest(
                WorkflowInputRequest.Action.SUPPLEMENT, "ART-1",
                "add liquidity arrangement", List.of());
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> submitAfterStart(fixture, request, ready, start));
            var second = pool.submit(() -> submitAfterStart(fixture, request, ready, start));
            assertThat(ready.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(first.get(5, java.util.concurrent.TimeUnit.SECONDS)).isNotNull();
            assertThat(second.get(5, java.util.concurrent.TimeUnit.SECONDS)).isNotNull();
        }

        verify(fixture.eventPublisher).publishEvent(any(KycRegenerationRequestedEvent.class));
        verify(fixture.agentStateMapper).updateById(any(AgentState.class));
        verify(fixture.workflowMapper).updateById(any(WorkflowState.class));
    }

    private Object submitAfterStart(
            Fixture fixture,
            WorkflowInputRequest request,
            java.util.concurrent.CountDownLatch ready,
            java.util.concurrent.CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(2, java.util.concurrent.TimeUnit.SECONDS)) {
            throw new IllegalStateException("concurrent test did not start");
        }
        return fixture.service.provideInput(principal(), "WF-1", "same-idempotency-key", request);
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
        WorkflowReviewMapper reviewMapper = mock(WorkflowReviewMapper.class);
        WorkflowAgentStateService agentStateService =
                new WorkflowAgentStateService(workflowMapper, agentStateMapper, artifactMapper);
        WorkflowService service = new WorkflowService(
                workflowMapper,
                agentStateMapper,
                artifactMapper,
                agentStateService,
                reviewMapper,
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
                customerDataMapper, importBatchMapper, reviewMapper);
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

    private AgentArtifact artifact(String artifactId, AgentType type) {
        AgentArtifact artifact = new AgentArtifact();
        artifact.setArtifactId(artifactId);
        artifact.setWorkflowId("WF-1");
        artifact.setAgentType(type);
        artifact.setVersion(1);
        return artifact;
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
            ImportBatchMapper importBatchMapper,
            WorkflowReviewMapper reviewMapper) {
    }
}
