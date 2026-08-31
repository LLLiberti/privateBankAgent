package com.privatebank.business.service.workflow;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.agent.adapter.workflow.AgentExecutionListener;
import com.privatebank.agent.application.downstream.DownstreamAgentExecutionService;
import com.privatebank.agent.application.kyc.*;
import com.privatebank.agent.application.runtime.*;
import com.privatebank.agent.domain.kyc.*;
import com.privatebank.agent.infrastructure.kyc.KycCustomerDataLoader;
import com.privatebank.business.dto.customer.CustomerSummaryResponse;
import com.privatebank.business.dto.admin.AdminWorkflowDeleteRequest;
import com.privatebank.business.dto.workflow.*;
import com.privatebank.business.entity.workflow.*;
import com.privatebank.business.enums.auth.RoleName;
import com.privatebank.business.enums.workflow.*;
import com.privatebank.business.mapper.customer.CustomerDataMapper;
import com.privatebank.business.mapper.workflow.*;
import com.privatebank.business.security.CurrentUserPrincipal;
import com.privatebank.business.security.CurrentUserService;
import com.privatebank.business.service.admin.AdminWorkflowCleanupService;
import com.privatebank.business.service.workflow.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:private_bank_integration;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "mybatis-plus.configuration.database-id=h2",
        "private-bank.graph.enabled=false"
})
@Import(WorkflowAgentH2IntegrationTest.EventRecorderConfiguration.class)
@Sql(scripts = "/workflow-h2-schema.sql")
class WorkflowAgentH2IntegrationTest {
    private static final long CUSTOMER_ID = 1001L;
    private static final long IMPORT_BATCH_ID = 2001L;
    private static final String MANAGER_DESCRIPTION = "客户经理补充的敏感原始描述不应进入持久化结果";

    @Autowired WorkflowService workflowService;
    @Autowired AdminWorkflowCleanupService adminWorkflowCleanupService;
    @Autowired KycWorkflowExecutionService kycExecutionService;
    @Autowired WorkflowAgentStateService agentStateService;
    @Autowired org.springframework.context.ApplicationEventPublisher eventPublisher;
    @Autowired WorkflowStateMapper workflowMapper;
    @Autowired AgentStateMapper agentMapper;
    @Autowired AgentArtifactMapper artifactMapper;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired EventRecorder recorder;

    @MockBean CurrentUserService currentUserService;
    @MockBean CustomerDataMapper customerDataMapper;
    @MockBean ImportBatchMapper importBatchMapper;
    @MockBean AgentExecutionListener agentExecutionListener;
    @MockBean DownstreamAgentExecutionService downstreamExecutionService;
    @MockBean KycCustomerDataLoader customerDataLoader;
    @Test
    void reportCenterMapperReturnsOnlyScopedReviewableAndExportableWorkflows() {
        jdbcTemplate.update(
                "INSERT INTO person(person_id, full_name, display_name) VALUES (?, ?, ?)",
                9001L, "李四", "P-9001");
        jdbcTemplate.update(
                "INSERT INTO user_customer_scope(user_id, person_id, scope_status) VALUES (?, ?, ?)",
                "USER-REPORT", 9001L, 1);

        workflowMapper.insert(reportWorkflow(
                "WF-REPORT-PENDING", "USER-REPORT", WorkflowStatus.WAITING_INPUT));
        workflowMapper.insert(reportWorkflow(
                "WF-REPORT-RUNNING", "USER-REPORT", WorkflowStatus.RUNNING));
        workflowMapper.insert(reportWorkflow(
                "WF-REPORT-OTHER", "OTHER-USER", WorkflowStatus.COMPLETED));

        LocalDateTime artifactTime = LocalDateTime.of(2026, 8, 21, 20, 1);
        insertReportArtifact("ART-CFS-PENDING", "WF-REPORT-PENDING",
                AgentType.SOLUTION_DESIGN, null, "{\"cfsVersion\":1}", artifactTime);
        insertReportArtifact("ART-COMPLIANCE-PENDING", "WF-REPORT-PENDING",
                AgentType.COMPLIANCE_CHECK, "REVIEW_REQUIRED",
                "{\"cfsArtifactRef\":\"ART-CFS-PENDING\"}", artifactTime.plusMinutes(1));
        insertReportArtifact("ART-CFS-RUNNING", "WF-REPORT-RUNNING",
                AgentType.SOLUTION_DESIGN, null, "{\"cfsVersion\":1}", artifactTime);
        insertReportArtifact("ART-COMPLIANCE-RUNNING", "WF-REPORT-RUNNING",
                AgentType.COMPLIANCE_CHECK, "REVIEW_REQUIRED",
                "{\"cfsArtifactRef\":\"ART-CFS-RUNNING\"}", artifactTime.plusMinutes(1));

        assertThat(workflowMapper.countForReportCenter("USER-REPORT", null, null)).isEqualTo(1);
        assertThat(workflowMapper.findForReportCenter(
                "USER-REPORT", null, "P-9001", 0, 20))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.workflowId()).isEqualTo("WF-REPORT-PENDING");
                    assertThat(row.customerName()).isEqualTo("P-9001");
                    assertThat(row.workflowStatus()).isEqualTo(WorkflowStatus.WAITING_INPUT);
                });
    }

    @Test
    void administratorPhysicallyDeletesAStableCfsWorkflowAggregate() {
        WorkflowState workflow = reportWorkflow(
                "WF-ADMIN-DELETE", "USER-REPORT", WorkflowStatus.WAITING_INPUT);
        workflowMapper.insert(workflow);
        jdbcTemplate.update("""
                INSERT INTO agent_state(
                    agent_state_id, workflow_id, agent_type, agent_status, execution_id,
                    retry_count, version, error_code, error_message, start_time, finish_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, CURRENT_TIMESTAMP)
                """, "AS-ADMIN-DELETE", workflow.getWorkflowId(), AgentType.CUSTOMER_INSIGHT.name(),
                AgentStatus.SUCCESS.name(), "EXE-ADMIN-DELETE", 0, 0L);
        insertReportArtifact(
                "ART-ADMIN-DELETE", workflow.getWorkflowId(), AgentType.CUSTOMER_INSIGHT,
                null, "{}", LocalDateTime.of(2026, 8, 21, 20, 1));
        jdbcTemplate.update("""
                INSERT INTO workflow_review(
                    workflow_id, reviewer_id, cfs_artifact_id, review_status,
                    review_comments, review_round, version, review_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, workflow.getWorkflowId(), "USER-REPORT", "ART-ADMIN-DELETE",
                ReviewStatus.REJECTED.name(), "test cleanup", 1, 0L);

        var response = adminWorkflowCleanupService.delete(
                new CurrentUserPrincipal("ADMIN-1", "administrator", RoleName.SYSTEM_ADMIN),
                workflow.getWorkflowId(),
                "integration-delete-1",
                new AdminWorkflowDeleteRequest(null, workflow.getVersion()));

        assertThat(response.deleted()).isTrue();
        assertThat(response.deletedAgentStates()).isEqualTo(1);
        assertThat(response.deletedArtifacts()).isEqualTo(1);
        assertThat(response.deletedReviews()).isEqualTo(1);
        assertThat(workflowMapper.selectById(workflow.getWorkflowId())).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_state WHERE workflow_id = ?", Long.class, workflow.getWorkflowId()))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_artifact WHERE workflow_id = ?", Long.class, workflow.getWorkflowId()))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_review WHERE workflow_id = ?", Long.class, workflow.getWorkflowId()))
                .isZero();
    }

    @MockBean KycDataMaskingService maskingService;
    @MockBean KycAgentExecutor kycExecutor;

    @Test
    void persistsFullWorkflowWithArtifactReferencesAndRuntimeSupplementIsolation() throws Exception {
        CurrentUserPrincipal manager = new CurrentUserPrincipal("USER-1", "manager", RoleName.CUSTOMER_MANAGER);
        when(customerDataMapper.findSummary(CUSTOMER_ID)).thenReturn(
                new CustomerSummaryResponse(CUSTOMER_ID, "客户一", "P-1001", "PERSON", "VERIFIED", "LOW"));
        when(importBatchMapper.isCompletedAndAvailableForCustomer(IMPORT_BATCH_ID, CUSTOMER_ID)).thenReturn(true);

        WorkflowCreatedResponse created = workflowService.create(manager, "IDEMPOTENCY-1",
                new CreateWorkflowRequest(CUSTOMER_ID, IMPORT_BATCH_ID, LocalDate.of(2026, 8, 1),
                        "PRIVATE_BANK_REVIEW", "固定分析范围"));
        assertThat(created.workflowStatus()).isEqualTo(WorkflowStatus.CREATED);
        assertThat(workflowMapper.selectById(created.workflowId()).getWorkflowStatus())
                .isEqualTo(WorkflowStatus.RUNNING);

        configureKyc();
        kycExecutionService.execute(recorder.latestRequested(AgentType.CUSTOMER_INSIGHT));
        AgentArtifact firstKyc = latest(created.workflowId(), AgentType.CUSTOMER_INSIGHT);
        assertKyc(firstKyc, 1);
        awaitWorkflowStatus(created.workflowId(), WorkflowStatus.WAITING_INPUT);

        workflowService.provideInput(manager, created.workflowId(), "IDEMPOTENCY-2",
                new WorkflowInputRequest(WorkflowInputRequest.Action.SUPPLEMENT, firstKyc.getArtifactId(),
                        MANAGER_DESCRIPTION, List.of("待确认的客户经理信号")));
        assertThat(state(created.workflowId(), AgentType.CUSTOMER_INSIGHT).getAgentStatus())
                .isEqualTo(AgentStatus.RUNNING);
        assertThat(workflowMapper.selectById(created.workflowId()).getWorkflowStatus())
                .isEqualTo(WorkflowStatus.RUNNING);

        kycExecutionService.execute(recorder.latestRequested(AgentType.CUSTOMER_INSIGHT));
        AgentArtifact secondKyc = latest(created.workflowId(), AgentType.CUSTOMER_INSIGHT);
        assertKyc(secondKyc, 2);
        assertThat(secondKyc.getArtifactId()).isNotEqualTo(firstKyc.getArtifactId());
        assertThat(secondKyc.getResult()).doesNotContain(MANAGER_DESCRIPTION);
        verify(maskingService).mask(any(KycCustomerData.class),
                eq(new KycRuntimeSupplement(
                        MANAGER_DESCRIPTION, List.of("待确认的客户经理信号"))));
        awaitWorkflowStatus(created.workflowId(), WorkflowStatus.WAITING_INPUT);

        workflowService.provideInput(manager, created.workflowId(), "IDEMPOTENCY-3",
                new WorkflowInputRequest(WorkflowInputRequest.Action.CONTINUE, secondKyc.getArtifactId(), null, null));
        assertThat(state(created.workflowId(), AgentType.MARKET_INSIGHT).getAgentStatus())
                .isEqualTo(AgentStatus.RUNNING);
        assertThat(state(created.workflowId(), AgentType.PRODUCT_EXPERT).getAgentStatus())
                .isEqualTo(AgentStatus.RUNNING);

        complete(recorder.latestRequested(AgentType.MARKET_INSIGHT), "{\"marketVersion\":\"M-1\"}", null);
        complete(recorder.latestRequested(AgentType.PRODUCT_EXPERT), "{\"productVersion\":\"P-1\"}", null);

        awaitAgentStatus(created.workflowId(), AgentType.SOLUTION_DESIGN, AgentStatus.RUNNING);
        complete(recorder.latestRequested(AgentType.SOLUTION_DESIGN), json(Map.of(
                "cfsVersion", "CFS-1", "inputArtifactIds", List.of(secondKyc.getArtifactId()))), null);
        AgentArtifact cfsArtifact = latest(created.workflowId(), AgentType.SOLUTION_DESIGN);

        awaitAgentStatus(created.workflowId(), AgentType.COMPLIANCE_CHECK, AgentStatus.RUNNING);
        complete(recorder.latestRequested(AgentType.COMPLIANCE_CHECK), json(Map.of(
                "cfsArtifactId", cfsArtifact.getArtifactId(),
                "cfsArtifactRef", cfsArtifact.getArtifactId())), "PASS");

        awaitWorkflowStatus(created.workflowId(), WorkflowStatus.WAITING_REVIEW);
        assertThat(artifactMapper.selectList(Wrappers.<AgentArtifact>lambdaQuery()
                .eq(AgentArtifact::getWorkflowId, created.workflowId()))).hasSize(6);
        assertThat(recorder.names()).containsSubsequence(
                "WORKFLOW_CREATED", "AGENT_COMPLETED:CUSTOMER_INSIGHT",
                "KYC_REGENERATION_REQUESTED", "AGENT_COMPLETED:CUSTOMER_INSIGHT",
                "DOWNSTREAM_AGENTS_READY");
        assertThat(recorder.names()).contains(
                "AGENT_COMPLETED:MARKET_INSIGHT", "AGENT_COMPLETED:PRODUCT_EXPERT",
                "AGENT_EXECUTION_REQUESTED:SOLUTION_DESIGN", "AGENT_COMPLETED:SOLUTION_DESIGN",
                "AGENT_EXECUTION_REQUESTED:COMPLIANCE_CHECK", "AGENT_COMPLETED:COMPLIANCE_CHECK");
        assertThat(recorder.requested()).extracting(AgentExecutionRequestedEvent::agentType)
                .contains(AgentType.SOLUTION_DESIGN, AgentType.COMPLIANCE_CHECK);
        assertThat(recorder.latestRequested(AgentType.SOLUTION_DESIGN).inputArtifactIds())
                .containsEntry("kycArtifactId", secondKyc.getArtifactId())
                .containsEntry("marketArtifactId", latest(created.workflowId(), AgentType.MARKET_INSIGHT).getArtifactId())
                .containsEntry("kypArtifactId", latest(created.workflowId(), AgentType.PRODUCT_EXPERT).getArtifactId());
        assertThat(recorder.latestRequested(AgentType.COMPLIANCE_CHECK).inputArtifactIds())
                .containsEntry("cfsArtifactId", cfsArtifact.getArtifactId());
    }

    @Test
    void answeringFollowUpQuestionKeepsWorkflowWaitingForManagerDecision() throws Exception {
        CurrentUserPrincipal manager = new CurrentUserPrincipal("USER-2", "manager", RoleName.CUSTOMER_MANAGER);
        when(customerDataMapper.findSummary(CUSTOMER_ID)).thenReturn(
                new CustomerSummaryResponse(CUSTOMER_ID, "Customer One", "P-1001", "PERSON", "VERIFIED", "LOW"));
        when(importBatchMapper.isCompletedAndAvailableForCustomer(IMPORT_BATCH_ID, CUSTOMER_ID)).thenReturn(true);

        WorkflowCreatedResponse created = workflowService.create(manager, "QA-IDEMPOTENCY-1",
                new CreateWorkflowRequest(CUSTOMER_ID, IMPORT_BATCH_ID, LocalDate.of(2026, 8, 1),
                        "PRIVATE_BANK_REVIEW", null));
        configureKyc();
        kycExecutionService.execute(recorder.latestRequested(AgentType.CUSTOMER_INSIGHT));
        AgentArtifact firstKyc = latest(created.workflowId(), AgentType.CUSTOMER_INSIGHT);

        awaitWorkflowStatus(created.workflowId(), WorkflowStatus.WAITING_INPUT);
        workflowService.provideInput(manager, created.workflowId(), "QA-IDEMPOTENCY-2",
                new WorkflowInputRequest(WorkflowInputRequest.Action.SUPPLEMENT, firstKyc.getArtifactId(),
                        null, List.of(),
                        List.of(new WorkflowInputRequest.Answer("Q1", "customer declines to confirm"))));
        assertThat(workflowMapper.selectById(created.workflowId()).getWorkflowStatus())
                .isEqualTo(WorkflowStatus.RUNNING);

        com.privatebank.business.dto.workflow.KycQaItem qa =
                new com.privatebank.business.dto.workflow.KycQaItem(
                        "Q1", "P-1001 liquidity arrangement?", "customer declines to confirm");
        kycExecutionService.execute(recorder.latestRequested(AgentType.CUSTOMER_INSIGHT));

        awaitWorkflowStatus(created.workflowId(), WorkflowStatus.WAITING_INPUT);
        assertThat(state(created.workflowId(), AgentType.MARKET_INSIGHT).getAgentStatus())
                .isEqualTo(AgentStatus.PENDING);
        assertThat(state(created.workflowId(), AgentType.PRODUCT_EXPERT).getAgentStatus())
                .isEqualTo(AgentStatus.PENDING);
        verify(maskingService).mask(any(KycCustomerData.class),
                eq(new KycRuntimeSupplement(null, List.of(), List.of(qa))));
    }

    private void insertReportArtifact(
            String artifactId, String workflowId, AgentType type,
            String complianceResult, String result, LocalDateTime createTime) {
        jdbcTemplate.update("""
                INSERT INTO agent_artifact(
                    artifact_id, workflow_id, agent_state_id, agent_type, execution_id,
                    compliance_result, result, storage_key, version, create_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, ?)
                """, artifactId, workflowId, "AS-" + artifactId, type.name(),
                "EXE-" + artifactId, complianceResult, result, 1, createTime);
    }

    private WorkflowState reportWorkflow(
            String workflowId, String createdBy, WorkflowStatus status) {
        WorkflowState workflow = new WorkflowState();
        workflow.setWorkflowId(workflowId);
        workflow.setPersonId(9001L);
        workflow.setCreatedBy(createdBy);
        workflow.setAsOfDate(LocalDate.of(2026, 8, 21));
        workflow.setTemplateId("CFS-3P6-V1");
        workflow.setWorkflowStatus(status);
        workflow.setVersion(0L);
        workflow.setCreatedAt(LocalDateTime.of(2026, 8, 21, 20, 0));
        workflow.setUpdatedAt(LocalDateTime.of(2026, 8, 21, 20, 0));
        return workflow;
    }

    private void configureKyc() {
        KycCustomerData data = org.mockito.Mockito.mock(KycCustomerData.class);
        KycMaskedInput masked = new KycMaskedInput(
                Map.of("person", Map.of("alias", "P-1001")), Map.of("SRC-1", 42L),
                Set.of("客户一"), Map.of("P-1001", "PERSON-1"), "a".repeat(64));
        when(customerDataLoader.load(CUSTOMER_ID)).thenReturn(data);
        when(maskingService.mask(eq(data), any(KycRuntimeSupplement.class))).thenReturn(masked);
        when(kycExecutor.agentType()).thenReturn(AgentType.CUSTOMER_INSIGHT);
        when(kycExecutor.execute(any(AgentExecutionRequest.class))).thenReturn(
                new AgentExecutionResult<>(structuredKyc(), 1, "mock-deepseek"));
        when(kycExecutor.toGenerationResult(any(AgentExecutionResult.class))).thenReturn(
                new KycGenerationResult(validKycJson(), 1, "mock-deepseek"));
    }

    private void assertKyc(AgentArtifact artifact, int version) throws Exception {
        assertThat(artifact).isNotNull();
        assertThat(artifact.getVersion()).isEqualTo(version);
        JsonNode root = objectMapper.readTree(artifact.getResult());
        assertThat(root.path("contractVersion").asText()).isEqualTo("kyc-result.v2");
        assertThat(root.path("model").asText()).isEqualTo("mock-deepseek");
        assertThat(root.path("maskingApplied").asBoolean()).isTrue();
        assertThat(root.path("maskedInputSha256").asText()).isEqualTo("a".repeat(64));
        assertThat(root.path("evidenceReferences").path("SRC-1").asLong()).isEqualTo(42L);
        assertThat(root.path("aliasMappings").path("P-1001").asText()).isEqualTo("PERSON-1");
        assertThat(root.path("analysis").path("findings").get(0).path("evidenceRefs").get(0).asText())
                .isEqualTo("SRC-1");
    }

    private String validKycJson() {
        return "{\"riskLevel\":\"LOW\",\"summary\":\"固定分析\","
                + "\"findings\":[{\"dimension\":\"PERSON\",\"riskLevel\":\"LOW\","
                + "\"finding\":\"固定证据支持\",\"evidenceRefs\":[\"SRC-1\"]}],"
                + "\"riskAlerts\":[],\"recommendedActions\":[],\"dataGaps\":[],"
                + "\"followUpQuestions\":[{\"id\":\"Q1\",\"question\":\"P-1001 liquidity arrangement?\"}],"
                + "\"graphAssessment\":{\"contribution\":\"NOT_AVAILABLE\","
                + "\"summary\":\"无图谱\",\"evidenceRefs\":[]}}";
    }

    private KycStructuredResult structuredKyc() {
        return new KycStructuredResult(KycStructuredResult.RiskLevel.LOW, "固定分析",
                List.of(new KycStructuredResult.Finding(KycStructuredResult.Dimension.PERSON,
                        KycStructuredResult.RiskLevel.LOW, "固定证据支持", List.of("SRC-1"))),
                List.of(), List.of(), List.of(),
                new KycStructuredResult.GraphAssessment(
                        KycStructuredResult.GraphContribution.NOT_AVAILABLE, "无图谱", List.of()));
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private void complete(
            AgentExecutionRequestedEvent requested,
            String resultJson,
            String complianceResult) {
        eventPublisher.publishEvent(new AgentExecutionCompletedEvent(
                requested.workflowId(),
                requested.agentStateId(),
                requested.agentType(),
                requested.executionId(),
                resultJson,
                complianceResult,
                0));
    }

    private void awaitWorkflowStatus(String workflowId, WorkflowStatus expected) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        WorkflowStatus actual = null;
        while (System.nanoTime() < deadline) {
            WorkflowState workflow = workflowMapper.selectById(workflowId);
            actual = workflow == null ? null : workflow.getWorkflowStatus();
            if (actual == expected) {
                return;
            }
            Thread.sleep(25);
        }
        assertThat(actual).isEqualTo(expected);
    }

    private void awaitAgentStatus(
            String workflowId,
            AgentType agentType,
            AgentStatus expected) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        AgentStatus actual = null;
        while (System.nanoTime() < deadline) {
            AgentState agentState = state(workflowId, agentType);
            actual = agentState == null ? null : agentState.getAgentStatus();
            if (actual == expected) {
                return;
            }
            Thread.sleep(25);
        }
        assertThat(actual).isEqualTo(expected);
    }

    private AgentState state(String workflowId, AgentType type) {
        return agentMapper.selectOne(Wrappers.<AgentState>lambdaQuery()
                .eq(AgentState::getWorkflowId, workflowId).eq(AgentState::getAgentType, type));
    }

    private AgentArtifact latest(String workflowId, AgentType type) {
        return artifactMapper.selectOne(Wrappers.<AgentArtifact>lambdaQuery()
                .eq(AgentArtifact::getWorkflowId, workflowId).eq(AgentArtifact::getAgentType, type)
                .orderByDesc(AgentArtifact::getVersion).last("LIMIT 1"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class EventRecorderConfiguration {
        @Bean EventRecorder eventRecorder() { return new EventRecorder(); }
    }

    @Order(Ordered.HIGHEST_PRECEDENCE)
    static class EventRecorder {
        private final List<String> names = new CopyOnWriteArrayList<>();
        private final List<AgentExecutionRequestedEvent> requested = new CopyOnWriteArrayList<>();

        @EventListener public void workflowCreated(WorkflowCreatedEvent event) { names.add("WORKFLOW_CREATED"); }
        @EventListener public void regeneration(KycRegenerationRequestedEvent event) {
            names.add("KYC_REGENERATION_REQUESTED");
        }
        @EventListener public void downstreamReady(DownstreamAgentsReadyEvent event) {
            names.add("DOWNSTREAM_AGENTS_READY");
        }
        @EventListener public void completed(AgentExecutionCompletedEvent event) {
            names.add("AGENT_COMPLETED:" + event.agentType());
        }
        @EventListener public void requested(AgentExecutionRequestedEvent event) {
            names.add("AGENT_EXECUTION_REQUESTED:" + event.agentType());
            requested.add(event);
        }

        List<String> names() { return List.copyOf(names); }
        List<AgentExecutionRequestedEvent> requested() { return List.copyOf(requested); }
        AgentExecutionRequestedEvent latestRequested(AgentType type) {
            return requested.stream()
                    .filter(event -> event.agentType() == type)
                    .reduce((left, right) -> right)
                    .orElseThrow(() -> new AssertionError("Missing execution request for " + type));
        }
    }
}
