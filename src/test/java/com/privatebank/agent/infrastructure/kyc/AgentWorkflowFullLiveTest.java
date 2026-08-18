package com.privatebank.agent.infrastructure.kyc;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.privatebank.business.mapper.workflow.AgentArtifactMapper;
import com.privatebank.business.mapper.workflow.AgentStateMapper;
import com.privatebank.business.mapper.workflow.WorkflowStateMapper;
import com.privatebank.business.security.CurrentUserPrincipal;
import com.privatebank.business.service.workflow.WorkflowService;
import com.privatebank.business.service.workflow.WorkflowEventHub;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.mockito.Mockito.doAnswer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

@Tag("live")
@EnabledIfSystemProperty(named = "private-bank.test.live-full-workflow", matches = "true")
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "private-bank.storage.root=./target/full-workflow-live-storage",
        "private-bank.storage.max-file-size-bytes=1048576"
})
class AgentWorkflowFullLiveTest {

    private static final Logger log = LoggerFactory.getLogger(AgentWorkflowFullLiveTest.class);

    private static final long PERSON_ID = Long.getLong("private-bank.test.person-id", 1L);
    private static final long IMPORT_BATCH_ID = Long.getLong("private-bank.test.import-batch-id", 1L);
    private static final String CUSTOMER_MANAGER_ID = "USER-DEMO-CUSTOMER-MANAGER";
    private static final String RAW_SUPPLEMENT = "LIQUIDITY_NEED_RAW_VALUE_" + UUID.randomUUID();
    private static final String DATASOURCE_URL = configuredProperty("spring.datasource.url", "");
    private static final String DATASOURCE_USERNAME = configuredProperty("spring.datasource.username", "");
    private static final String DATASOURCE_PASSWORD = configuredProperty("spring.datasource.password", "");
    private static final String NEO4J_URI = configuredProperty("spring.neo4j.uri", "");
    private static final String NEO4J_USERNAME = configuredProperty("spring.neo4j.authentication.username", "");
    private static final String NEO4J_PASSWORD = configuredProperty("spring.neo4j.authentication.password", "");
    private static final String NEO4J_DATABASE = configuredProperty("spring.neo4j.database", "neo4j");
    private static final String ES_URIS = configuredProperty("spring.elasticsearch.uris", "");
    private static final String ES_USERNAME = configuredProperty("spring.elasticsearch.username", "");
    private static final String ES_PASSWORD = configuredProperty("spring.elasticsearch.password", "");
    private static final String DEEPSEEK_API_KEY = configuredProperty("private-bank.agent-runtime.deepseek.api-key", "");
    private static final String DEEPSEEK_BASE_URL = configuredProperty("private-bank.agent-runtime.deepseek.base-url", "");
    private static final String DEEPSEEK_MODEL = configuredProperty("private-bank.agent-runtime.deepseek.model", "");

    @DynamicPropertySource
    static void liveProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DATASOURCE_URL);
        registry.add("spring.datasource.username", () -> DATASOURCE_USERNAME);
        registry.add("spring.datasource.password", () -> DATASOURCE_PASSWORD);
        registry.add("spring.flyway.enabled", () -> false);
        registry.add("spring.neo4j.uri", () -> NEO4J_URI);
        registry.add("spring.neo4j.authentication.username", () -> NEO4J_USERNAME);
        registry.add("spring.neo4j.authentication.password", () -> NEO4J_PASSWORD);
        registry.add("spring.neo4j.database", () -> NEO4J_DATABASE);
        registry.add("private-bank.graph.enabled", () -> true);
        registry.add("spring.elasticsearch.uris", () -> ES_URIS);
        registry.add("spring.elasticsearch.username", () -> ES_USERNAME);
        registry.add("spring.elasticsearch.password", () -> ES_PASSWORD);
        registry.add("private-bank.agent-runtime.deepseek.api-key", () -> DEEPSEEK_API_KEY);
        registry.add("private-bank.agent-runtime.deepseek.base-url", () -> DEEPSEEK_BASE_URL);
        registry.add("private-bank.agent-runtime.deepseek.model", () -> DEEPSEEK_MODEL);
    }

    @BeforeAll
    static void requireLiveConfiguration() {
        Assertions.assertFalse(DATASOURCE_URL.isBlank(), "main application.yml must configure the live database URL");
        Assertions.assertFalse(DEEPSEEK_API_KEY.isBlank(), "main application.yml must configure the live model API key");
        Assertions.assertFalse(NEO4J_URI.isBlank(), "main application.yml must configure the live Neo4j URI");
    }

    @Autowired
    private WorkflowService workflowService;


    @Autowired
    private WorkflowStateMapper workflowMapper;


    @Autowired
    private AgentStateMapper agentStateMapper;


    @Autowired
    private AgentArtifactMapper artifactMapper;


    @Autowired
    private ObjectMapper objectMapper;

    @SpyBean
    private WorkflowEventHub workflowEventHub;

    private SseEmitter activeSseEmitter;

    @BeforeEach
    void configureSseLogging() {
        doAnswer(invocation -> {
            String workflowId = invocation.getArgument(0);
            String eventName = invocation.getArgument(1);
            Object payload = invocation.getArgument(2);
            log.info("SSE事件：工作流={}，事件={}（{}），内容={}",
                    workflowId, eventName, describeSseEvent(eventName), json(payload));
            return invocation.callRealMethod();
        }).when(workflowEventHub).publish(anyString(), anyString(), any());
    }

    @AfterEach
    void closeSseSubscription() {
        if (activeSseEmitter != null) {
            activeSseEmitter.complete();
            activeSseEmitter = null;
        }
    }
    @Test
    void completesWorkflowFromStartThroughKycSupplementParallelAnalysisCfsAndCompliance() throws Exception {
        CurrentUserPrincipal manager = managerPrincipal();
        long workflowStarted = System.nanoTime();
        WorkflowCreatedResponse created = workflowService.create(
                manager,
                "full-live-create-" + UUID.randomUUID(),
                new CreateWorkflowRequest(
                        PERSON_ID,
                        IMPORT_BATCH_ID,
                        LocalDate.now(),
                        "CFS-3P6-V1",
                        "Generate a complete customer service solution from the selected customer data."));
        logDuration("工作流启动", workflowStarted);
        activeSseEmitter = workflowService.subscribe(manager, created.workflowId());
        log.info("SSE订阅建立：工作流={}，开始接收后续事件", created.workflowId());

        WorkflowState initialKyc = awaitStatus(created.workflowId(), WorkflowStatus.WAITING_INPUT);
        logDuration("首次KYC分析", workflowStarted);
        AgentArtifact initialArtifact = latestArtifact(created.workflowId(), AgentType.CUSTOMER_INSIGHT);
        assertThat(initialKyc.getWorkflowStatus()).isEqualTo(WorkflowStatus.WAITING_INPUT);
        assertThat(agentState(created.workflowId(), AgentType.CUSTOMER_INSIGHT).getAgentStatus())
                .isEqualTo(AgentStatus.SUCCESS);
        assertThat(initialArtifact).isNotNull();
        assertThat(initialArtifact.getVersion()).isEqualTo(1);
        assertThat(initialArtifact.getResult()).contains("\"contractVersion\":\"kyc-result.v2\"");

        long supplementStarted = System.nanoTime();
        WorkflowDetailResponse supplementAccepted = workflowService.provideInput(
                manager,
                created.workflowId(),
                "full-live-supplement-" + UUID.randomUUID(),
                new WorkflowInputRequest(
                        WorkflowInputRequest.Action.SUPPLEMENT,
                        initialArtifact.getArtifactId(),
                        RAW_SUPPLEMENT,
                        List.of("liquidity need")));
        assertThat(supplementAccepted.workflowStatus()).isEqualTo(WorkflowStatus.RUNNING);
        logDuration("客户经理补充提交", supplementStarted);

        WorkflowState regenerated = awaitStatus(created.workflowId(), WorkflowStatus.WAITING_INPUT);
        AgentArtifact regeneratedArtifact = latestArtifact(created.workflowId(), AgentType.CUSTOMER_INSIGHT);
        assertThat(regenerated.getWorkflowStatus()).isEqualTo(WorkflowStatus.WAITING_INPUT);
        assertThat(regeneratedArtifact.getVersion()).isEqualTo(2);
        assertThat(regeneratedArtifact.getResult()).doesNotContain(RAW_SUPPLEMENT);
        logDuration("二次KYC分析", supplementStarted);

        long approvalStarted = System.nanoTime();
        WorkflowDetailResponse managerApproved = workflowService.provideInput(
                manager,
                created.workflowId(),
                "full-live-approve-" + UUID.randomUUID(),
                new WorkflowInputRequest(
                        WorkflowInputRequest.Action.CONTINUE,
                        regeneratedArtifact.getArtifactId(),
                        null,
                        List.of()));
        assertThat(managerApproved.workflowStatus()).isEqualTo(WorkflowStatus.RUNNING);
        assertThat(agentState(created.workflowId(), AgentType.MARKET_INSIGHT).getAgentStatus())
                .isEqualTo(AgentStatus.READY);
        assertThat(agentState(created.workflowId(), AgentType.PRODUCT_EXPERT).getAgentStatus())
                .isEqualTo(AgentStatus.READY);
        logDuration("客户经理通过", approvalStarted);

        long downstreamStarted = System.nanoTime();
        awaitParallelAgentSuccesses(created.workflowId(), downstreamStarted);

        long cfsStarted = System.nanoTime();
        awaitAgentSuccess(created.workflowId(), AgentType.SOLUTION_DESIGN);
        AgentArtifact cfs = assertSuccessWithArtifact(created.workflowId(), AgentType.SOLUTION_DESIGN);
        logDuration("CFS生成", cfsStarted);

        long complianceStarted = System.nanoTime();
        awaitAgentSuccess(created.workflowId(), AgentType.COMPLIANCE_CHECK);
        AgentArtifact compliance = assertSuccessWithArtifact(created.workflowId(), AgentType.COMPLIANCE_CHECK);
        logDuration("合规校验", complianceStarted);

        WorkflowState finalState = awaitStatus(created.workflowId(), WorkflowStatus.WAITING_REVIEW);
        logDuration("工作流进入待人工审核", complianceStarted);
        assertThat(finalState.getWorkflowStatus())
                .withFailMessage(() -> diagnostic(created.workflowId()))
                .isEqualTo(WorkflowStatus.WAITING_REVIEW);

        assertThat(cfs.getResult()).contains("inputArtifactRefs", "cfsStructure", "comprehensiveRiskAssessment");
        assertThat(compliance.getComplianceResult()).isEqualTo("PASS");
        JsonNode complianceJson = objectMapper.readTree(compliance.getResult());
        assertThat(complianceJson.path("cfsArtifactRef").asText()).isEqualTo(cfs.getArtifactId());
    }

    private void awaitParallelAgentSuccesses(String workflowId, long startedNanos) {
        Set<AgentType> logged = EnumSet.noneOf(AgentType.class);
        Awaitility.await()
                .atMost(Duration.ofMinutes(15))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    assertAgentSuccess(workflowId, AgentType.MARKET_INSIGHT, "竞争分析", startedNanos, logged);
                    assertAgentSuccess(workflowId, AgentType.PRODUCT_EXPERT, "产品匹配", startedNanos, logged);
                });
    }

    private void assertAgentSuccess(
            String workflowId, AgentType type, String phase, long startedNanos, Set<AgentType> logged) {
        AgentState state = agentState(workflowId, type);
        assertThat(state)
                .withFailMessage(() -> diagnostic(workflowId))
                .isNotNull();
        if (state.getAgentStatus() == AgentStatus.SUCCESS && logged.add(type)) {
            logDuration(phase, startedNanos);
        }
        assertThat(state.getAgentStatus())
                .withFailMessage(() -> diagnostic(workflowId))
                .isEqualTo(AgentStatus.SUCCESS);
    }

    private void awaitAgentSuccess(String workflowId, AgentType type) {
        Awaitility.await()
                .atMost(Duration.ofMinutes(15))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> assertSuccessWithArtifact(workflowId, type));
    }

    private void logDuration(String phase, long startedNanos) {
        log.info("阶段完成：{}，耗时={}毫秒", phase, (System.nanoTime() - startedNanos) / 1_000_000);
    }

    private String describeSseEvent(String eventName) {
        return switch (eventName) {
            case "WORKFLOW_CREATED" -> "工作流已创建";
            case "KYC_REGENERATION_REQUESTED" -> "已请求KYC再次分析";
            case "KYC_ANALYSIS_COMPLETED" -> "KYC分析完成";
            case "DOWNSTREAM_AGENTS_READY" -> "竞争分析和产品匹配已就绪";
            case "COMPLIANCE_PASSED" -> "合规校验通过";
            case "AGENT_PROGRESS" -> "Agent运行进度";
            case "AGENT_FAILED" -> "Agent执行失败";
            default -> "工作流事件";
        };
    }

    private String json(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception exception) {
            return String.valueOf(payload);
        }
    }
    private WorkflowState awaitStatus(String workflowId, WorkflowStatus status) {
        Awaitility.await()
                .atMost(Duration.ofMinutes(10))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> assertThat(workflowMapper.selectById(workflowId).getWorkflowStatus())
                        .isEqualTo(status));
        return workflowMapper.selectById(workflowId);
    }

    private WorkflowState awaitWorkflowCompletionOrFailure(String workflowId) {
        Awaitility.await()
                .atMost(Duration.ofMinutes(15))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> assertThat(workflowMapper.selectById(workflowId).getWorkflowStatus())
                        .isIn(WorkflowStatus.WAITING_REVIEW, WorkflowStatus.FAILED));
        return workflowMapper.selectById(workflowId);
    }

    private AgentArtifact assertSuccessWithArtifact(String workflowId, AgentType type) {
        AgentState state = agentState(workflowId, type);
        assertThat(state)
                .withFailMessage(() -> diagnostic(workflowId))
                .isNotNull();
        assertThat(state.getAgentStatus())
                .withFailMessage(() -> diagnostic(workflowId))
                .isEqualTo(AgentStatus.SUCCESS);
        AgentArtifact artifact = latestArtifact(workflowId, type);
        assertThat(artifact).isNotNull();
        assertThat(artifact.getExecutionId()).isEqualTo(state.getExecutionId());
        return artifact;
    }

    private String diagnostic(String workflowId) {
        WorkflowState workflow = workflowMapper.selectById(workflowId);
        String agents = java.util.Arrays.stream(AgentType.values())
                .map(type -> {
                    AgentState state = agentState(workflowId, type);
                    return type + "=" + (state == null ? null
                            : state.getAgentStatus() + "/" + state.getErrorCode() + "/" + state.getErrorMessage());
                })
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        return "workflow=" + workflow.getWorkflowStatus() + "/" + workflow.getErrorCode()
                + "/" + workflow.getErrorMessage() + ", agents=" + agents;
    }

    private AgentState agentState(String workflowId, AgentType type) {
        return agentStateMapper.selectOne(Wrappers.<AgentState>lambdaQuery()
                .eq(AgentState::getWorkflowId, workflowId)
                .eq(AgentState::getAgentType, type));
    }

    private AgentArtifact latestArtifact(String workflowId, AgentType type) {
        return artifactMapper.selectOne(Wrappers.<AgentArtifact>lambdaQuery()
                .eq(AgentArtifact::getWorkflowId, workflowId)
                .eq(AgentArtifact::getAgentType, type)
                .orderByDesc(AgentArtifact::getVersion)
                .last("LIMIT 1"));
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
            throw new IllegalStateException("Unable to read live workflow test configuration", exception);
        }
    }

    private static String resolveEnvironmentPlaceholder(String value) {
        if (!value.startsWith("${") || !value.endsWith("}")) {
            return value;
        }
        String expression = value.substring(2, value.length() - 1);
        int separator = expression.indexOf(58);
        String environmentKey = separator < 0 ? expression : expression.substring(0, separator);
        String defaultValue = separator < 0 ? "" : expression.substring(separator + 1);
        String environmentValue = System.getenv(environmentKey);
        return environmentValue == null || environmentValue.isBlank() ? defaultValue : environmentValue;
    }

    private CurrentUserPrincipal managerPrincipal() {
        return new CurrentUserPrincipal(
                CUSTOMER_MANAGER_ID, "full-workflow-live-test", RoleName.CUSTOMER_MANAGER);
    }
}
