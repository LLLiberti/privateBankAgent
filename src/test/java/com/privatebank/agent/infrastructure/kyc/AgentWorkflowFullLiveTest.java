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
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("live")
@EnabledIfSystemProperty(named = "private-bank.test.live-full-workflow", matches = "true")
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "private-bank.storage.root=./target/full-workflow-live-storage",
        "private-bank.storage.max-file-size-bytes=1048576"
})
class AgentWorkflowFullLiveTest {

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

    @Test
    void completesWorkflowFromStartThroughKycSupplementParallelAnalysisCfsAndCompliance() throws Exception {
        CurrentUserPrincipal manager = managerPrincipal();
        WorkflowCreatedResponse created = workflowService.create(
                manager,
                "full-live-create-" + UUID.randomUUID(),
                new CreateWorkflowRequest(
                        PERSON_ID,
                        IMPORT_BATCH_ID,
                        LocalDate.now(),
                        "CFS-3P6-V1",
                        "Generate a complete customer service solution from the selected customer data."));

        WorkflowState initialKyc = awaitStatus(created.workflowId(), WorkflowStatus.WAITING_INPUT);
        AgentArtifact initialArtifact = latestArtifact(created.workflowId(), AgentType.CUSTOMER_INSIGHT);
        assertThat(initialKyc.getWorkflowStatus()).isEqualTo(WorkflowStatus.WAITING_INPUT);
        assertThat(agentState(created.workflowId(), AgentType.CUSTOMER_INSIGHT).getAgentStatus())
                .isEqualTo(AgentStatus.SUCCESS);
        assertThat(initialArtifact).isNotNull();
        assertThat(initialArtifact.getVersion()).isEqualTo(1);
        assertThat(initialArtifact.getResult()).contains("\"contractVersion\":\"kyc-result.v2\"");

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

        WorkflowState regenerated = awaitStatus(created.workflowId(), WorkflowStatus.WAITING_INPUT);
        AgentArtifact regeneratedArtifact = latestArtifact(created.workflowId(), AgentType.CUSTOMER_INSIGHT);
        assertThat(regenerated.getWorkflowStatus()).isEqualTo(WorkflowStatus.WAITING_INPUT);
        assertThat(regeneratedArtifact.getVersion()).isEqualTo(2);
        assertThat(regeneratedArtifact.getResult()).doesNotContain(RAW_SUPPLEMENT);

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

        WorkflowState finalState = awaitWorkflowCompletionOrFailure(created.workflowId());
        assertThat(finalState.getWorkflowStatus())
                .withFailMessage(() -> diagnostic(created.workflowId()))
                .isEqualTo(WorkflowStatus.WAITING_REVIEW);

        assertSuccessWithArtifact(created.workflowId(), AgentType.MARKET_INSIGHT);
        assertSuccessWithArtifact(created.workflowId(), AgentType.PRODUCT_EXPERT);
        AgentArtifact cfs = assertSuccessWithArtifact(created.workflowId(), AgentType.SOLUTION_DESIGN);
        AgentArtifact compliance = assertSuccessWithArtifact(created.workflowId(), AgentType.COMPLIANCE_CHECK);

        assertThat(cfs.getResult()).contains("inputArtifactRefs", "cfsStructure", "comprehensiveRiskAssessment");
        assertThat(compliance.getComplianceResult()).isEqualTo("PASS");
        JsonNode complianceJson = objectMapper.readTree(compliance.getResult());
        assertThat(complianceJson.path("cfsArtifactRef").asText()).isEqualTo(cfs.getArtifactId());
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
