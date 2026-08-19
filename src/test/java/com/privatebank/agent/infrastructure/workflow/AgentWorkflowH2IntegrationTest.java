package com.privatebank.agent.infrastructure.workflow;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.agent.adapter.workflow.DownstreamAgentWorkflowListener;
import com.privatebank.agent.adapter.workflow.KycWorkflowListener;
import com.privatebank.agent.application.downstream.DownstreamAgentExecutionService;
import com.privatebank.agent.application.kyc.*;
import com.privatebank.agent.application.runtime.*;
import com.privatebank.agent.domain.event.AgentExecutionRequestedEvent;
import com.privatebank.agent.domain.event.AgentSucceededEvent;
import com.privatebank.agent.domain.kyc.*;
import com.privatebank.agent.infrastructure.kyc.KycCustomerDataLoader;
import com.privatebank.business.dto.customer.CustomerSummaryResponse;
import com.privatebank.business.dto.workflow.*;
import com.privatebank.business.entity.workflow.*;
import com.privatebank.business.enums.auth.RoleName;
import com.privatebank.business.enums.workflow.*;
import com.privatebank.business.mapper.customer.CustomerDataMapper;
import com.privatebank.business.mapper.workflow.*;
import com.privatebank.business.security.CurrentUserPrincipal;
import com.privatebank.business.security.CurrentUserService;
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
import org.springframework.test.context.jdbc.Sql;

import java.time.LocalDate;
import java.util.*;

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
        "private-bank.graph.enabled=false"
})
@Import(AgentWorkflowH2IntegrationTest.EventRecorderConfiguration.class)
@Sql(scripts = "/workflow-h2-schema.sql")
class AgentWorkflowH2IntegrationTest {
    private static final long CUSTOMER_ID = 1001L;
    private static final long IMPORT_BATCH_ID = 2001L;
    private static final String MANAGER_DESCRIPTION = "客户经理补充的敏感原始描述不应进入持久化结果";

    @Autowired WorkflowService workflowService;
    @Autowired KycWorkflowExecutionService kycExecutionService;
    @Autowired AgentWorkflowStateService agentStateService;
    @Autowired WorkflowStateMapper workflowMapper;
    @Autowired AgentStateMapper agentMapper;
    @Autowired AgentArtifactMapper artifactMapper;
    @Autowired ObjectMapper objectMapper;
    @Autowired EventRecorder recorder;

    @MockBean CurrentUserService currentUserService;
    @MockBean CustomerDataMapper customerDataMapper;
    @MockBean ImportBatchMapper importBatchMapper;
    @MockBean KycWorkflowListener kycWorkflowListener;
    @MockBean DownstreamAgentWorkflowListener downstreamListener;
    @MockBean DownstreamAgentExecutionService downstreamExecutionService;
    @MockBean KycCustomerDataLoader customerDataLoader;
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
                .isEqualTo(WorkflowStatus.CREATED);

        configureKyc();
        kycExecutionService.execute(created.workflowId());
        AgentArtifact firstKyc = latest(created.workflowId(), AgentType.CUSTOMER_INSIGHT);
        assertKyc(firstKyc, 1);
        assertThat(workflowMapper.selectById(created.workflowId()).getWorkflowStatus())
                .isEqualTo(WorkflowStatus.WAITING_INPUT);

        workflowService.provideInput(manager, created.workflowId(), "IDEMPOTENCY-2",
                new WorkflowInputRequest(WorkflowInputRequest.Action.SUPPLEMENT, firstKyc.getArtifactId(),
                        MANAGER_DESCRIPTION, List.of("待确认的客户经理信号")));
        assertThat(state(created.workflowId(), AgentType.CUSTOMER_INSIGHT).getAgentStatus())
                .isEqualTo(AgentStatus.READY);
        assertThat(workflowMapper.selectById(created.workflowId()).getWorkflowStatus())
                .isEqualTo(WorkflowStatus.RUNNING);

        kycExecutionService.execute(created.workflowId(),
                new KycRuntimeSupplement(null, List.of("待确认的客户经理信号")));
        AgentArtifact secondKyc = latest(created.workflowId(), AgentType.CUSTOMER_INSIGHT);
        assertKyc(secondKyc, 2);
        assertThat(secondKyc.getArtifactId()).isNotEqualTo(firstKyc.getArtifactId());
        assertThat(secondKyc.getResult()).doesNotContain(MANAGER_DESCRIPTION);
        verify(maskingService).mask(any(KycCustomerData.class),
                eq(new KycRuntimeSupplement(null, List.of("待确认的客户经理信号"))));

        workflowService.provideInput(manager, created.workflowId(), "IDEMPOTENCY-3",
                new WorkflowInputRequest(WorkflowInputRequest.Action.CONTINUE, secondKyc.getArtifactId(), null, null));
        assertThat(state(created.workflowId(), AgentType.MARKET_INSIGHT).getAgentStatus())
                .isEqualTo(AgentStatus.READY);
        assertThat(state(created.workflowId(), AgentType.PRODUCT_EXPERT).getAgentStatus())
                .isEqualTo(AgentStatus.READY);

        AgentExecutionClaim market = agentStateService.claim(created.workflowId(), AgentType.MARKET_INSIGHT).orElseThrow();
        agentStateService.complete(market, "{\"marketVersion\":\"M-1\"}", null);
        AgentExecutionClaim product = agentStateService.claim(created.workflowId(), AgentType.PRODUCT_EXPERT).orElseThrow();
        agentStateService.complete(product, "{\"productVersion\":\"P-1\"}", null);

        assertThat(state(created.workflowId(), AgentType.SOLUTION_DESIGN).getAgentStatus())
                .isEqualTo(AgentStatus.READY);
        AgentExecutionClaim cfs = agentStateService.claim(created.workflowId(), AgentType.SOLUTION_DESIGN).orElseThrow();
        agentStateService.complete(cfs, json(Map.of("cfsVersion", "CFS-1",
                "inputArtifactIds", List.of(secondKyc.getArtifactId()))), null);
        AgentArtifact cfsArtifact = latest(created.workflowId(), AgentType.SOLUTION_DESIGN);

        assertThat(state(created.workflowId(), AgentType.COMPLIANCE_CHECK).getAgentStatus())
                .isEqualTo(AgentStatus.READY);
        AgentExecutionClaim compliance = agentStateService.claim(
                created.workflowId(), AgentType.COMPLIANCE_CHECK).orElseThrow();
        agentStateService.complete(compliance, json(Map.of(
                "cfsArtifactId", cfsArtifact.getArtifactId(),
                "cfsArtifactRef", cfsArtifact.getArtifactId())), "PASS");

        assertThat(workflowMapper.selectById(created.workflowId()).getWorkflowStatus())
                .isEqualTo(WorkflowStatus.WAITING_REVIEW);
        assertThat(artifactMapper.selectList(Wrappers.<AgentArtifact>lambdaQuery()
                .eq(AgentArtifact::getWorkflowId, created.workflowId()))).hasSize(6);
        assertThat(recorder.names()).containsSubsequence(
                "WORKFLOW_CREATED", "AGENT_SUCCEEDED:CUSTOMER_INSIGHT",
                "KYC_REGENERATION_REQUESTED", "AGENT_SUCCEEDED:CUSTOMER_INSIGHT",
                "DOWNSTREAM_AGENTS_READY");
        assertThat(recorder.names()).contains(
                "AGENT_SUCCEEDED:MARKET_INSIGHT", "AGENT_SUCCEEDED:PRODUCT_EXPERT",
                "AGENT_EXECUTION_REQUESTED:SOLUTION_DESIGN", "AGENT_SUCCEEDED:SOLUTION_DESIGN",
                "AGENT_EXECUTION_REQUESTED:COMPLIANCE_CHECK", "AGENT_SUCCEEDED:COMPLIANCE_CHECK");        assertThat(recorder.requested()).extracting(AgentExecutionRequestedEvent::agentType)
                .containsExactly(AgentType.SOLUTION_DESIGN, AgentType.COMPLIANCE_CHECK);
        assertThat(recorder.requested().get(0).inputArtifactIds())
                .containsEntry("kycArtifactId", secondKyc.getArtifactId())
                .containsEntry("marketArtifactId", latest(created.workflowId(), AgentType.MARKET_INSIGHT).getArtifactId())
                .containsEntry("kypArtifactId", latest(created.workflowId(), AgentType.PRODUCT_EXPERT).getArtifactId());
        assertThat(recorder.requested().get(1).inputArtifactIds())
                .containsEntry("cfsArtifactId", cfsArtifact.getArtifactId());
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
        private final List<String> names = new ArrayList<>();
        private final List<AgentExecutionRequestedEvent> requested = new ArrayList<>();

        @EventListener public void workflowCreated(WorkflowCreatedEvent event) { names.add("WORKFLOW_CREATED"); }
        @EventListener public void regeneration(KycRegenerationRequestedEvent event) {
            names.add("KYC_REGENERATION_REQUESTED");
        }
        @EventListener public void downstreamReady(DownstreamAgentsReadyEvent event) {
            names.add("DOWNSTREAM_AGENTS_READY");
        }
        @EventListener public void succeeded(AgentSucceededEvent event) {
            names.add("AGENT_SUCCEEDED:" + event.agentType());
        }
        @EventListener public void requested(AgentExecutionRequestedEvent event) {
            names.add("AGENT_EXECUTION_REQUESTED:" + event.agentType());
            requested.add(event);
        }

        List<String> names() { return List.copyOf(names); }
        List<AgentExecutionRequestedEvent> requested() { return List.copyOf(requested); }
    }
}
