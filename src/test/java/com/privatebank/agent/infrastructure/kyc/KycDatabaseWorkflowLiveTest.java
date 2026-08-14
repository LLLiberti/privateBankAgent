package com.privatebank.agent.infrastructure.kyc;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.agent.adapter.workflow.KycWorkflowListener;
import com.privatebank.agent.application.kyc.KycRuntimeSupplement;
import com.privatebank.agent.application.kyc.KycRuntimeSupplementProjector;
import com.privatebank.agent.application.kyc.KycAgentExecutor;
import com.privatebank.agent.application.kyc.KycDataMaskingService;
import com.privatebank.agent.application.kyc.KycGraphDataLoader;
import com.privatebank.agent.application.kyc.KycOutputValidator;
import com.privatebank.agent.application.kyc.KycWorkflowExecutionService;
import com.privatebank.agent.application.runtime.AgentProgressPublisher;
import com.privatebank.agent.config.AgentScopeConfiguration;
import com.privatebank.agent.config.AgentScopeProperties;
import com.privatebank.agent.infrastructure.agentscope.AgentRuntimeContextFactory;
import com.privatebank.agent.infrastructure.agentscope.AgentScopeExecutionEngine;
import com.privatebank.agent.domain.kyc.KycCustomerData;
import com.privatebank.agent.domain.kyc.KycMaskedInput;
import com.privatebank.business.common.idempotency.IdempotencyExecutor;
import com.privatebank.business.common.exception.BusinessException;
import com.privatebank.business.common.exception.ErrorCode;
import com.privatebank.business.config.MybatisPlusConfig;
import com.privatebank.business.config.StorageProperties;
import com.privatebank.business.dto.workflow.CreateWorkflowRequest;
import com.privatebank.business.dto.workflow.WorkflowDetailResponse;
import com.privatebank.business.dto.workflow.WorkflowInputRequest;
import com.privatebank.business.dto.workflow.WorkflowCreatedResponse;
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
import com.privatebank.business.mapper.workflow.WorkflowStateMapper;
import com.privatebank.business.security.CurrentUserPrincipal;
import com.privatebank.business.security.CurrentUserService;
import com.privatebank.business.service.document.FileStorageService;
import com.privatebank.business.service.workflow.WorkflowAgentResultListener;
import com.privatebank.business.service.workflow.WorkflowEventHub;
import com.privatebank.business.service.workflow.WorkflowService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import io.agentscope.core.model.Model;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Explicitly enabled integration test against the configured database and model.
 * It deliberately retains the generated workflow and artifact for manual review.
 */
@Tag("live")
@EnabledIfSystemProperty(named = "private-bank.test.live-db-kyc", matches = "true")
@SpringJUnitConfig
@ContextConfiguration(classes = KycDatabaseWorkflowLiveTest.LiveDatabaseWorkflowConfiguration.class)
class KycDatabaseWorkflowLiveTest {

    private static final String NOT_CREATED_WORKFLOW_ID = "NOT_CREATED";
    private static final long PERSON_ID = Long.getLong("private-bank.test.person-id", 1L);
    private static final long IMPORT_BATCH_ID = 1L;
    private static final String CUSTOMER_MANAGER_ID = "USER-DEMO-CUSTOMER-MANAGER";
    private static final String API_KEY = configuredProperty("private-bank.agent-runtime.deepseek.api-key", "");
    private static final String BASE_URL = configuredProperty(
            "private-bank.agent-runtime.deepseek.base-url", "https://api.deepseek.com/v1");
    private static final String MODEL = configuredProperty(
            "private-bank.agent-runtime.deepseek.model", "deepseek-v4-flash");
    private static final String DATASOURCE_URL = configuredProperty("spring.datasource.url", "");
    private static final String DATASOURCE_USERNAME = configuredProperty("spring.datasource.username", "");
    private static final String DATASOURCE_PASSWORD = configuredProperty("spring.datasource.password", "");
    private static final String NEO4J_URI = configuredProperty("spring.neo4j.uri", "");
    private static final String NEO4J_USERNAME = configuredProperty("spring.neo4j.authentication.username", "");
    private static final String NEO4J_PASSWORD = configuredProperty("spring.neo4j.authentication.password", "");
    private static final String NEO4J_DATABASE = configuredProperty("spring.neo4j.database", "neo4j");

    @DynamicPropertySource
    static void liveProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DATASOURCE_URL);
        registry.add("spring.datasource.username", () -> DATASOURCE_USERNAME);
        registry.add("spring.datasource.password", () -> DATASOURCE_PASSWORD);
        registry.add("spring.datasource.hikari.max-lifetime", () -> 240000L);
        registry.add("spring.datasource.hikari.keepalive-time", () -> 60000L);
        registry.add("spring.datasource.hikari.validation-timeout", () -> 5000L);
        registry.add("spring.flyway.enabled", () -> false);
        registry.add("spring.neo4j.uri", () -> NEO4J_URI);
        registry.add("spring.neo4j.authentication.username", () -> NEO4J_USERNAME);
        registry.add("spring.neo4j.authentication.password", () -> NEO4J_PASSWORD);
        registry.add("spring.neo4j.database", () -> NEO4J_DATABASE);
        registry.add("private-bank.storage.root", () -> "./target/live-kyc-test-storage");
        registry.add("private-bank.storage.max-file-size-bytes", () -> 1048576L);
    }

    @BeforeAll
    static void requireLiveConfiguration() {
        Assertions.assertFalse(DATASOURCE_URL.isBlank(), "application.yml must configure the database URL");
        Assertions.assertFalse(API_KEY.isBlank(), "application.yml must configure the DeepSeek API key");
        Assertions.assertFalse(NEO4J_URI.isBlank(), "application.yml must configure the Neo4j URI");
    }

    @org.springframework.beans.factory.annotation.Autowired
    private CustomerDataMapper customerDataMapper;

    @org.springframework.beans.factory.annotation.Autowired
    private KycCustomerDataLoader customerDataLoader;

    @org.springframework.beans.factory.annotation.Autowired
    private KycDataMaskingService dataMaskingService;

    @org.springframework.beans.factory.annotation.Autowired
    private KycRuntimeSupplementProjector runtimeSupplementProjector;

    @org.springframework.beans.factory.annotation.Autowired
    private WorkflowService workflowService;

    @org.springframework.beans.factory.annotation.Autowired
    private WorkflowStateMapper workflowStateMapper;

    @org.springframework.beans.factory.annotation.Autowired
    private AgentStateMapper agentStateMapper;

    @org.springframework.beans.factory.annotation.Autowired
    private AgentArtifactMapper artifactMapper;

    @org.springframework.beans.factory.annotation.Autowired
    private ObjectMapper objectMapper;

    @Test
    void executesManagerSupplementRegenerationAndApprovalFlowAgainstLiveDatabaseAndModel() throws Exception {
        long testStartedNanos = System.nanoTime();
        String workflowId = NOT_CREATED_WORKFLOW_ID;
        boolean completed = false;
        logTiming("START", "TEST_TOTAL", workflowId, testStartedNanos, testStartedNanos, "RUNNING");
        try {
            timed(workflowId, "VERIFY_LIVE_CUSTOMER", testStartedNanos, () -> {
                assertThat(customerDataMapper.findSummary(PERSON_ID))
                        .as("personId=%s must exist in the configured database", PERSON_ID)
                        .isNotNull();
                return null;
            });

            KycCustomerData rawCustomerData = timed(workflowId, "LOAD_AND_MASK_LOCAL_DATA", testStartedNanos, () -> {
                KycCustomerData customerData = customerDataLoader.load(PERSON_ID);
                assertThat(customerData.graphRelationships())
                        .as("personId=%s must have graph relationships in the configured Neo4j database", PERSON_ID)
                        .isNotEmpty();
                KycMaskedInput maskedInput = dataMaskingService.mask(customerData);
                System.out.printf("[KYC agent runtime] phase=MASKING_COMPLETED evidenceRefCount=%d prohibitedTermCount=%d decision=invoke-model-with-masked-input-only%n"
                                + "[KYC masked input] sha256=%s payload=%s%n",
                        maskedInput.evidenceReferences().size(), maskedInput.prohibitedTerms().size(),
                        maskedInput.sha256(), objectMapper.writeValueAsString(maskedInput.payload()));
                return customerData;
            });
            KycMaskedInput expectedMaskedInput = dataMaskingService.mask(rawCustomerData);
            WorkflowCreatedResponse created = timed(workflowId, "CREATE_WORKFLOW", testStartedNanos, () -> workflowService.create(
                    new CurrentUserPrincipal(CUSTOMER_MANAGER_ID, "live-kyc-test", RoleName.CUSTOMER_MANAGER),
                    "live-kyc-" + UUID.randomUUID(),
                    new CreateWorkflowRequest(
                            PERSON_ID,
                            IMPORT_BATCH_ID,
                            LocalDate.now(),
                            "KYC-LIVE-TEST",
                            "Execute the standard KYC analysis for the selected customer.")));
            workflowId = created.workflowId();
        System.out.printf("[KYC agent runtime] phase=WORKFLOW_CREATED workflowId=%s status=%s decision=wait-for-async-kyc%n",
                created.workflowId(), created.workflowStatus());

        WorkflowState workflow = timed(workflowId, "WAIT_INITIAL_KYC_ANALYSIS", testStartedNanos,
                () -> awaitKycOutcome(created.workflowId()));
        AgentState kycState = kycState(created.workflowId());
        AgentArtifact artifact = latestKycArtifact(created.workflowId());

        JsonNode analysis = timed(workflowId, "VALIDATE_INITIAL_KYC_ARTIFACT", testStartedNanos, () -> {
            assertThat(workflow.getPersonId()).isEqualTo(PERSON_ID);
            assertThat(workflow.getWorkflowStatus())
                    .withFailMessage("KYC workflow failed: workflowErrorCode=%s, workflowErrorMessage=%s, agentStatus=%s, agentErrorCode=%s, agentErrorMessage=%s",
                            workflow.getErrorCode(), workflow.getErrorMessage(), kycState == null ? null : kycState.getAgentStatus(),
                            kycState == null ? null : kycState.getErrorCode(), kycState == null ? null : kycState.getErrorMessage())
                    .isEqualTo(WorkflowStatus.WAITING_INPUT);
            assertThat(kycState).isNotNull();
            assertThat(kycState.getAgentStatus()).isEqualTo(AgentStatus.SUCCESS);
            assertThat(kycState.getExecutionId()).isNotBlank();
            assertThat(artifact).isNotNull();
            assertThat(artifact.getAgentStateId()).isEqualTo(kycState.getAgentStateId());
            assertThat(artifact.getExecutionId()).isEqualTo(kycState.getExecutionId());
            assertThat(artifact.getVersion()).isEqualTo(1);

            JsonNode savedResult = objectMapper.readTree(artifact.getResult());
            JsonNode initialAnalysis = savedResult.path("analysis");
            assertThat(savedResult.path("maskingApplied").asBoolean()).isTrue();
            assertThat(savedResult.path("maskedInputSha256").asText()).isEqualTo(expectedMaskedInput.sha256());
            assertThat(savedResult.path("model").asText()).isEqualTo(MODEL);
            assertThat(initialAnalysis.fieldNames()).toIterable().containsExactlyInAnyOrder(
                    "riskLevel", "summary", "findings", "riskAlerts", "recommendedActions", "dataGaps",
                    "graphAssessment");
            for (String prohibitedValue : expectedMaskedInput.prohibitedTerms()) {
                assertThat(artifact.getResult()).doesNotContain(prohibitedValue);
            }
            return initialAnalysis;
        });
        System.out.printf("[KYC agent runtime] phase=ARTIFACT_VALIDATED workflowId=%s agentStatus=%s decision=kyc-result-is-ready-for-manager-confirmation%n",
                created.workflowId(), kycState.getAgentStatus());

        timed(workflowId, "VALIDATE_EMPTY_SUPPLEMENT_REJECTED", testStartedNanos, () -> {
            assertThatThrownBy(() -> workflowService.provideInput(
                    managerPrincipal(),
                    created.workflowId(),
                    "live-kyc-empty-supplement-" + UUID.randomUUID(),
                    new WorkflowInputRequest(WorkflowInputRequest.Action.SUPPLEMENT, artifact.getArtifactId(), " ", List.of())))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(exception -> assertThat(((BusinessException) exception).getCode())
                            .isEqualTo(ErrorCode.INVALID_ARGUMENT));
            assertThat(workflowStateMapper.selectById(created.workflowId()).getWorkflowStatus())
                    .isEqualTo(WorkflowStatus.WAITING_INPUT);
            assertThat(kycArtifacts(created.workflowId())).hasSize(1);
            return null;
        });

        String rawManagerSupplement = "LIVE_RUNTIME_SUPPLEMENT_DO_NOT_PERSIST_20260812";
        List<String> confirmedItems = List.of("客户近期存在流动性安排需求");
        KycMaskedInput regeneratedMaskedInput = timed(workflowId, "PROJECT_RUNTIME_SUPPLEMENT", testStartedNanos, () -> {
            KycRuntimeSupplement supplement = runtimeSupplementProjector.project(rawManagerSupplement, confirmedItems);
            KycMaskedInput maskedInput = dataMaskingService.mask(rawCustomerData, supplement);
            assertThat(supplement.signals()).contains("LIQUIDITY_NEED").doesNotContain(rawManagerSupplement);
            assertThat(objectMapper.writeValueAsString(maskedInput.payload()))
                    .contains("managerSupplement", "LIQUIDITY_NEED")
                    .doesNotContain(rawManagerSupplement);
            return maskedInput;
        });

        WorkflowDetailResponse supplementAccepted = timed(workflowId, "SUBMIT_MANAGER_SUPPLEMENT", testStartedNanos,
                () -> workflowService.provideInput(
                        managerPrincipal(),
                        created.workflowId(),
                        "live-kyc-supplement-" + UUID.randomUUID(),
                        new WorkflowInputRequest(
                                WorkflowInputRequest.Action.SUPPLEMENT,
                                artifact.getArtifactId(),
                                rawManagerSupplement,
                                confirmedItems)));
        timed(workflowId, "VERIFY_SUPPLEMENT_ACCEPTED", testStartedNanos, () -> {
            assertThat(supplementAccepted.workflowStatus()).isEqualTo(WorkflowStatus.RUNNING);
            assertThat(agentState(supplementAccepted, AgentType.CUSTOMER_INSIGHT).agentStatus()).isEqualTo(AgentStatus.READY);
            return null;
        });
        System.out.printf("[KYC agent runtime] phase=MANAGER_SUPPLEMENT_ACCEPTED workflowId=%s decision=regenerate-kyc-with-runtime-only-signals%n",
                created.workflowId());

        WorkflowState regeneratedWorkflow = timed(workflowId, "WAIT_REGENERATED_KYC_ANALYSIS", testStartedNanos,
                () -> awaitKycOutcome(created.workflowId()));
        AgentState regeneratedKycState = kycState(created.workflowId());
        AgentArtifact regeneratedArtifact = latestKycArtifact(created.workflowId());
        timed(workflowId, "VALIDATE_REGENERATED_KYC_ARTIFACT", testStartedNanos, () -> {
            assertThat(regeneratedWorkflow.getWorkflowStatus()).isEqualTo(WorkflowStatus.WAITING_INPUT);
            assertThat(regeneratedWorkflow.getAnalysisRequirements())
                    .isEqualTo("Execute the standard KYC analysis for the selected customer.")
                    .doesNotContain(rawManagerSupplement);
            assertThat(regeneratedKycState.getAgentStatus()).isEqualTo(AgentStatus.SUCCESS);
            assertThat(regeneratedArtifact.getVersion()).isEqualTo(2);
            assertThat(regeneratedArtifact.getArtifactId()).isNotEqualTo(artifact.getArtifactId());
            assertThat(regeneratedArtifact.getExecutionId()).isEqualTo(regeneratedKycState.getExecutionId())
                    .isNotEqualTo(kycState.getExecutionId());
            assertThat(kycArtifacts(created.workflowId())).extracting(AgentArtifact::getVersion)
                    .containsExactly(1, 2);
            JsonNode regeneratedResult = objectMapper.readTree(regeneratedArtifact.getResult());
            assertThat(regeneratedResult.path("maskedInputSha256").asText()).isEqualTo(regeneratedMaskedInput.sha256());
            assertKycArtifactIsSafeAndContractValid(regeneratedArtifact, regeneratedMaskedInput, rawManagerSupplement);
            return null;
        });
        System.out.printf("[KYC agent runtime] phase=KYC_REGENERATED workflowId=%s artifactId=%s version=%d decision=manager-can-approve-latest-kyc%n",
                created.workflowId(), regeneratedArtifact.getArtifactId(), regeneratedArtifact.getVersion());

        timed(workflowId, "VALIDATE_STALE_ARTIFACT_REJECTED", testStartedNanos, () -> {
            assertThatThrownBy(() -> workflowService.provideInput(
                    managerPrincipal(),
                    created.workflowId(),
                    "live-kyc-stale-approval-" + UUID.randomUUID(),
                    new WorkflowInputRequest(WorkflowInputRequest.Action.CONTINUE, artifact.getArtifactId(), null, List.of())))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(exception -> assertThat(((BusinessException) exception).getCode())
                            .isEqualTo(ErrorCode.STALE_ARTIFACT));
            assertThat(workflowStateMapper.selectById(created.workflowId()).getWorkflowStatus())
                    .isEqualTo(WorkflowStatus.WAITING_INPUT);
            assertThat(agentState(created.workflowId(), AgentType.MARKET_INSIGHT).getAgentStatus()).isEqualTo(AgentStatus.PENDING);
            assertThat(agentState(created.workflowId(), AgentType.PRODUCT_EXPERT).getAgentStatus()).isEqualTo(AgentStatus.PENDING);
            return null;
        });

        WorkflowDetailResponse approvalAccepted = timed(workflowId, "APPROVE_LATEST_KYC_AND_RELEASE_DOWNSTREAM", testStartedNanos,
                () -> workflowService.provideInput(
                        managerPrincipal(),
                        created.workflowId(),
                        "live-kyc-approval-" + UUID.randomUUID(),
                        new WorkflowInputRequest(
                                WorkflowInputRequest.Action.CONTINUE,
                                regeneratedArtifact.getArtifactId(),
                                null,
                                List.of())));
        WorkflowState approvedWorkflow = timed(workflowId, "VERIFY_DOWNSTREAM_RELEASE", testStartedNanos, () -> {
            assertThat(approvalAccepted.workflowStatus()).isEqualTo(WorkflowStatus.RUNNING);
            assertThat(agentState(approvalAccepted, AgentType.MARKET_INSIGHT).agentStatus()).isEqualTo(AgentStatus.READY);
            assertThat(agentState(approvalAccepted, AgentType.PRODUCT_EXPERT).agentStatus()).isEqualTo(AgentStatus.READY);

            WorkflowState finalWorkflow = workflowStateMapper.selectById(created.workflowId());
            assertThat(finalWorkflow.getWorkflowStatus()).isEqualTo(WorkflowStatus.RUNNING);
            assertThat(agentState(created.workflowId(), AgentType.MARKET_INSIGHT).getAgentStatus()).isEqualTo(AgentStatus.READY);
            assertThat(agentState(created.workflowId(), AgentType.PRODUCT_EXPERT).getAgentStatus()).isEqualTo(AgentStatus.READY);
            assertThat(kycArtifacts(created.workflowId())).extracting(AgentArtifact::getVersion)
                    .containsExactly(1, 2);
            return finalWorkflow;
        });
        System.out.printf("[KYC agent runtime] phase=MANAGER_APPROVED workflowId=%s decision=downstream-agents-ready%n",
                created.workflowId());

        System.out.printf("%n[KYC database live test] workflowId=%s personId=%d workflowStatus=%s agentStatus=%s artifactId=%s%n"
                        + "[KYC database live test] maskedInput=%s%n[KYC database live test] savedAnalysis=%s%n"
                        +"[KYC 结果] kycResult artifact=%s",
                created.workflowId(), PERSON_ID, approvedWorkflow.getWorkflowStatus(), regeneratedKycState.getAgentStatus(), regeneratedArtifact.getArtifactId(),
                objectMapper.writeValueAsString(expectedMaskedInput.payload()), analysis, artifact);
            completed = true;
        } finally {
            logTiming("END", "TEST_TOTAL", workflowId, testStartedNanos, testStartedNanos,
                    completed ? "SUCCESS" : "FAILED");
        }
    }

    private <T> T timed(String workflowId, String step, long testStartedNanos, TimedOperation<T> operation) throws Exception {
        long stepStartedNanos = System.nanoTime();
        logTiming("START", step, workflowId, stepStartedNanos, testStartedNanos, "RUNNING");
        try {
            T result = operation.execute();
            logTiming("END", step, workflowId, stepStartedNanos, testStartedNanos, "SUCCESS");
            return result;
        } catch (RuntimeException | Error exception) {
            logTiming("END", step, workflowId, stepStartedNanos, testStartedNanos,
                    "FAILED:" + exception.getClass().getSimpleName());
            throw exception;
        } catch (Exception exception) {
            logTiming("END", step, workflowId, stepStartedNanos, testStartedNanos,
                    "FAILED:" + exception.getClass().getSimpleName());
            throw exception;
        }
    }

    private void logTiming(String event, String step, String workflowId, long stepStartedNanos,
                           long testStartedNanos, String outcome) {
        long now = System.nanoTime();
        System.out.printf("[KYC live test timing] event=%s step=%s workflowId=%s stepElapsedMs=%d totalElapsedMs=%d outcome=%s%n",
                event, step, workflowId, nanosToMillis(now - stepStartedNanos),
                nanosToMillis(now - testStartedNanos), outcome);
    }

    private long nanosToMillis(long nanos) {
        return nanos / 1_000_000;
    }

    @FunctionalInterface
    private interface TimedOperation<T> {

        T execute() throws Exception;
    }

    private CurrentUserPrincipal managerPrincipal() {
        return new CurrentUserPrincipal(CUSTOMER_MANAGER_ID, "live-kyc-test", RoleName.CUSTOMER_MANAGER);
    }

    private WorkflowState awaitKycOutcome(String workflowId) {
        Awaitility.await()
                .atMost(Duration.ofMinutes(8))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> assertThat(workflowStateMapper.selectById(workflowId).getWorkflowStatus())
                        .isIn(WorkflowStatus.WAITING_INPUT, WorkflowStatus.FAILED));
        WorkflowState workflow = workflowStateMapper.selectById(workflowId);
        AgentState state = kycState(workflowId);
        AgentArtifact latestArtifact = latestKycArtifact(workflowId);
        assertThat(workflow.getWorkflowStatus())
                .withFailMessage("KYC workflow failed: workflowErrorCode=%s, workflowErrorMessage=%s, agentStatus=%s, agentExecutionId=%s, agentErrorCode=%s, agentErrorMessage=%s, latestArtifactVersion=%s, latestArtifactExecutionId=%s",
                        workflow.getErrorCode(), workflow.getErrorMessage(), state == null ? null : state.getAgentStatus(),
                        state == null ? null : state.getExecutionId(), state == null ? null : state.getErrorCode(),
                        state == null ? null : state.getErrorMessage(), latestArtifact == null ? null : latestArtifact.getVersion(),
                        latestArtifact == null ? null : latestArtifact.getExecutionId())
                .isEqualTo(WorkflowStatus.WAITING_INPUT);
        return workflow;
    }

    private AgentState kycState(String workflowId) {
        return agentState(workflowId, AgentType.CUSTOMER_INSIGHT);
    }

    private AgentState agentState(String workflowId, AgentType agentType) {
        return agentStateMapper.selectOne(Wrappers.<AgentState>lambdaQuery()
                .eq(AgentState::getWorkflowId, workflowId)
                .eq(AgentState::getAgentType, agentType));
    }

    private AgentArtifact latestKycArtifact(String workflowId) {
        return artifactMapper.selectOne(Wrappers.<AgentArtifact>lambdaQuery()
                .eq(AgentArtifact::getWorkflowId, workflowId)
                .eq(AgentArtifact::getAgentType, AgentType.CUSTOMER_INSIGHT)
                .orderByDesc(AgentArtifact::getVersion)
                .last("LIMIT 1"));
    }

    private List<AgentArtifact> kycArtifacts(String workflowId) {
        return artifactMapper.selectList(Wrappers.<AgentArtifact>lambdaQuery()
                .eq(AgentArtifact::getWorkflowId, workflowId)
                .eq(AgentArtifact::getAgentType, AgentType.CUSTOMER_INSIGHT)
                .orderByAsc(AgentArtifact::getVersion));
    }

    private void assertKycArtifactIsSafeAndContractValid(
            AgentArtifact artifact, KycMaskedInput maskedInput, String rawManagerSupplement) throws Exception {
        assertThat(artifact).isNotNull();
        JsonNode result = objectMapper.readTree(artifact.getResult());
        JsonNode analysis = result.path("analysis");
        assertThat(result.path("maskingApplied").asBoolean()).isTrue();
        assertThat(result.path("maskedInputSha256").asText()).isEqualTo(maskedInput.sha256());
        assertThat(result.path("model").asText()).isEqualTo(MODEL);
        assertThat(analysis.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "riskLevel", "summary", "findings", "riskAlerts", "recommendedActions", "dataGaps",
                "graphAssessment");
        assertThat(artifact.getResult()).doesNotContain(rawManagerSupplement);
        for (String prohibitedValue : maskedInput.prohibitedTerms()) {
            assertThat(artifact.getResult()).doesNotContain(prohibitedValue);
        }
    }

    private com.privatebank.business.dto.workflow.AgentStateResponse agentState(
            WorkflowDetailResponse workflow, AgentType agentType) {
        return workflow.agentStates().stream()
                .filter(state -> state.agentType() == agentType)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing agent state for " + agentType));
    }

    @Configuration(proxyBeanMethods = false)
    @Import({MybatisPlusConfig.class, KycAsyncConfiguration.class})
    @EnableConfigurationProperties(StorageProperties.class)
    @ImportAutoConfiguration({
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            TransactionAutoConfiguration.class,
            MybatisPlusAutoConfiguration.class
    })
    static class LiveDatabaseWorkflowConfiguration {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        KycOutputValidator kycOutputValidator(ObjectMapper objectMapper) {
            return new KycOutputValidator(objectMapper);
        }

        @Bean
        AgentScopeProperties agentScopeProperties() {
            return new AgentScopeProperties(
                    new AgentScopeProperties.DeepSeek(BASE_URL, API_KEY, MODEL, 0D), 2, 4, 2);
        }

        @Bean
        Model privateBankAgentModel(AgentScopeProperties properties) {
            return new AgentScopeConfiguration().privateBankAgentModel(properties);
        }

        @Bean
        AgentRuntimeContextFactory agentRuntimeContextFactory() {
            return new AgentRuntimeContextFactory();
        }

        @Bean
        AgentProgressPublisher agentProgressPublisher() {
            return ignored -> { };
        }

        @Bean
        AgentScopeExecutionEngine agentScopeExecutionEngine(
                Model model,
                AgentScopeProperties properties,
                AgentRuntimeContextFactory contextFactory,
                AgentProgressPublisher progressPublisher) {
            return new AgentScopeExecutionEngine(model, properties, contextFactory, progressPublisher);
        }

        @Bean
        KycAgentExecutor kycAgentExecutor(
                AgentScopeExecutionEngine runtime,
                KycOutputValidator validator,
                ObjectMapper objectMapper,
                AgentScopeProperties properties) {
            return new KycAgentExecutor(runtime, validator, objectMapper, properties);
        }

        @Bean
        KycDataMaskingService kycDataMaskingService(ObjectMapper objectMapper) {
            return new KycDataMaskingService(objectMapper);
        }

        @Bean
        KycGraphDataLoader kycGraphDataLoader() {
            return new Neo4jKycGraphDataLoader(NEO4J_URI, NEO4J_USERNAME, NEO4J_PASSWORD, NEO4J_DATABASE);
        }

        @Bean
        KycCustomerDataLoader kycCustomerDataLoader(
                CustomerDataMapper customerDataMapper, KycGraphDataLoader graphDataLoader) {
            return new KycCustomerDataLoader(customerDataMapper, graphDataLoader);
        }

        @Bean
        KycWorkflowStateService kycWorkflowStateService(
                WorkflowStateMapper workflowStateMapper,
                AgentStateMapper agentStateMapper,
                AgentArtifactMapper agentArtifactMapper,
                org.springframework.context.ApplicationEventPublisher eventPublisher,
                ObjectMapper objectMapper) {
            return new KycWorkflowStateService(
                    workflowStateMapper, agentStateMapper, agentArtifactMapper, eventPublisher, objectMapper);
        }

        @Bean
        KycWorkflowExecutionService kycWorkflowExecutionService(
                KycWorkflowStateService stateService,
                KycCustomerDataLoader dataLoader,
                KycDataMaskingService maskingService,
                KycAgentExecutor kycAgentExecutor) {
            return new KycWorkflowExecutionService(stateService, dataLoader, maskingService, kycAgentExecutor);
        }

        @Bean
        KycRuntimeSupplementProjector kycRuntimeSupplementProjector() {
            return new KycRuntimeSupplementProjector();
        }

        @Bean
        KycWorkflowListener kycWorkflowListener(
                KycWorkflowExecutionService executionService,
                KycRuntimeSupplementProjector runtimeSupplementProjector) {
            return new KycWorkflowListener(executionService, runtimeSupplementProjector);
        }

        @Bean
        WorkflowEventHub workflowEventHub() {
            return new WorkflowEventHub();
        }

        @Bean
        WorkflowAgentResultListener workflowAgentResultListener(
                WorkflowStateMapper workflowStateMapper,
                AgentStateMapper agentStateMapper,
                AgentArtifactMapper agentArtifactMapper,
                WorkflowEventHub eventHub) {
            return new WorkflowAgentResultListener(
                    workflowStateMapper, agentStateMapper, agentArtifactMapper, eventHub);
        }

        @Bean
        IdempotencyExecutor idempotencyExecutor() {
            return new IdempotencyExecutor(180);
        }

        @Bean
        CurrentUserService currentUserService(
                com.privatebank.business.mapper.auth.SysUserMapper userMapper,
                com.privatebank.business.mapper.auth.SysRoleMapper roleMapper,
                com.privatebank.business.mapper.auth.UserCustomerScopeMapper scopeMapper) {
            return new CurrentUserService(userMapper, roleMapper, scopeMapper);
        }

        @Bean
        FileStorageService fileStorageService(StorageProperties storageProperties) {
            return new FileStorageService(storageProperties);
        }

        @Bean
        WorkflowService workflowService(
                WorkflowStateMapper workflowStateMapper,
                AgentStateMapper agentStateMapper,
                AgentArtifactMapper agentArtifactMapper,
                com.privatebank.business.mapper.workflow.WorkflowReviewMapper reviewMapper,
                CustomerDataMapper customerDataMapper,
                com.privatebank.business.mapper.workflow.ImportBatchMapper importBatchMapper,
                CurrentUserService currentUserService,
                IdempotencyExecutor idempotencyExecutor,
                WorkflowEventHub eventHub,
                org.springframework.context.ApplicationEventPublisher eventPublisher,
                ObjectMapper objectMapper,
                FileStorageService fileStorageService) {
            return new WorkflowService(
                    workflowStateMapper, agentStateMapper, agentArtifactMapper, reviewMapper, customerDataMapper, importBatchMapper,
                    currentUserService, idempotencyExecutor, eventHub, eventPublisher, objectMapper, fileStorageService);
        }

    }

    private static String configuredProperty(String name, String fallback) {
        try {
            List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
                    "application.yml", new FileSystemResource("src/main/resources/application.yml"));
            for (PropertySource<?> source : sources) {
                Object value = source.getProperty(name);
                if (value != null) {
                    return resolveEnvironmentPlaceholder(String.valueOf(value));
                }
            }
            return fallback;
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取真实数据库 KYC 测试配置", exception);
        }
    }

    private static String resolveEnvironmentPlaceholder(String value) {
        if (!value.startsWith("${") || !value.endsWith("}")) {
            return value;
        }
        String expression = value.substring(2, value.length() - 1);
        int separator = expression.indexOf(':');
        String environmentKey = separator < 0 ? expression : expression.substring(0, separator);
        String defaultValue = separator < 0 ? "" : expression.substring(separator + 1);
        String environmentValue = System.getenv(environmentKey);
        return environmentValue == null || environmentValue.isBlank() ? defaultValue : environmentValue;
    }
}
