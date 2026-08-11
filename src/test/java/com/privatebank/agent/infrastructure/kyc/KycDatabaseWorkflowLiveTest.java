package com.privatebank.agent.infrastructure.kyc;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.agent.adapter.workflow.KycWorkflowListener;
import com.privatebank.agent.application.kyc.KycAnalysisGenerator;
import com.privatebank.agent.application.kyc.KycDataMaskingService;
import com.privatebank.agent.application.kyc.KycModelClient;
import com.privatebank.agent.application.kyc.KycOutputValidator;
import com.privatebank.agent.application.kyc.KycWorkflowExecutionService;
import com.privatebank.agent.domain.kyc.KycMaskedInput;
import com.privatebank.business.common.idempotency.IdempotencyExecutor;
import com.privatebank.business.config.StorageProperties;
import com.privatebank.business.dto.workflow.CreateWorkflowRequest;
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
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

/**
 * Explicitly enabled integration test against the configured database and model.
 * It deliberately retains the generated workflow and artifact for manual review.
 */
@Tag("live")
@EnabledIfSystemProperty(named = "private-bank.test.live-db-kyc", matches = "true")
@SpringJUnitConfig
@ContextConfiguration(classes = KycDatabaseWorkflowLiveTest.LiveDatabaseWorkflowConfiguration.class)
class KycDatabaseWorkflowLiveTest {

    private static final long PERSON_ID = 1L;
    private static final long IMPORT_BATCH_ID = 1L;
    private static final String CUSTOMER_MANAGER_ID = "USER-DEMO-CUSTOMER-MANAGER";
    private static final String API_KEY = configuredProperty("spring.ai.deepseek.api-key", "");
    private static final String BASE_URL = configuredProperty("spring.ai.deepseek.base-url", "https://api.deepseek.com");
    private static final String MODEL = configuredProperty("spring.ai.deepseek.chat.options.model", "deepseek-v4-flash");
    private static final String DATASOURCE_URL = configuredProperty("spring.datasource.url", "");
    private static final String DATASOURCE_USERNAME = configuredProperty("spring.datasource.username", "");
    private static final String DATASOURCE_PASSWORD = configuredProperty("spring.datasource.password", "");

    @DynamicPropertySource
    static void liveProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DATASOURCE_URL);
        registry.add("spring.datasource.username", () -> DATASOURCE_USERNAME);
        registry.add("spring.datasource.password", () -> DATASOURCE_PASSWORD);
        registry.add("spring.flyway.enabled", () -> false);
        registry.add("spring.ai.deepseek.base-url", () -> BASE_URL);
        registry.add("spring.ai.deepseek.api-key", () -> API_KEY);
        registry.add("spring.ai.deepseek.chat.options.model", () -> MODEL);
        registry.add("spring.ai.deepseek.chat.options.temperature", () -> 0);
        registry.add("private-bank.storage.root", () -> "./target/live-kyc-test-storage");
        registry.add("private-bank.storage.max-file-size-bytes", () -> 1048576L);
    }

    @BeforeAll
    static void requireLiveConfiguration() {
        Assertions.assertFalse(DATASOURCE_URL.isBlank(), "application.yml must configure the database URL");
        Assertions.assertFalse(API_KEY.isBlank(), "application.yml must configure the DeepSeek API key");
    }

    @org.springframework.beans.factory.annotation.Autowired
    private CustomerDataMapper customerDataMapper;

    @org.springframework.beans.factory.annotation.Autowired
    private KycCustomerDataLoader customerDataLoader;

    @org.springframework.beans.factory.annotation.Autowired
    private KycDataMaskingService dataMaskingService;

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
    void createsAndExecutesKycWorkflowForDatabasePersonOne() throws Exception {
        assertThat(customerDataMapper.findSummary(PERSON_ID))
                .as("personId=1 must exist in the configured database")
                .isNotNull();

        KycMaskedInput expectedMaskedInput = dataMaskingService.mask(customerDataLoader.load(PERSON_ID));
        WorkflowCreatedResponse created = workflowService.create(
                new CurrentUserPrincipal(CUSTOMER_MANAGER_ID, "live-kyc-test", RoleName.CUSTOMER_MANAGER),
                "live-kyc-" + UUID.randomUUID(),
                new CreateWorkflowRequest(
                        PERSON_ID,
                        IMPORT_BATCH_ID,
                        LocalDate.now(),
                        "KYC-LIVE-TEST",
                        "Execute the standard KYC analysis for the selected customer."));

        Awaitility.await()
                .atMost(Duration.ofMinutes(2))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> assertThat(workflowStateMapper.selectById(created.workflowId())
                        .getWorkflowStatus()).isIn(WorkflowStatus.WAITING_INPUT, WorkflowStatus.FAILED));

        WorkflowState workflow = workflowStateMapper.selectById(created.workflowId());
        AgentState kycState = agentStateMapper.selectOne(Wrappers.<AgentState>lambdaQuery()
                .eq(AgentState::getWorkflowId, created.workflowId())
                .eq(AgentState::getAgentType, AgentType.CUSTOMER_INSIGHT));
        AgentArtifact artifact = artifactMapper.selectOne(Wrappers.<AgentArtifact>lambdaQuery()
                .eq(AgentArtifact::getWorkflowId, created.workflowId())
                .eq(AgentArtifact::getAgentType, AgentType.CUSTOMER_INSIGHT));

        assertThat(workflow.getPersonId()).isEqualTo(PERSON_ID);
        assertThat(workflow.getWorkflowStatus()).isEqualTo(WorkflowStatus.WAITING_INPUT);
        assertThat(kycState).isNotNull();
        assertThat(kycState.getAgentStatus()).isEqualTo(AgentStatus.SUCCESS);
        assertThat(kycState.getExecutionId()).isNotBlank();
        assertThat(artifact).isNotNull();
        assertThat(artifact.getAgentStateId()).isEqualTo(kycState.getAgentStateId());
        assertThat(artifact.getExecutionId()).isEqualTo(kycState.getExecutionId());
        assertThat(artifact.getVersion()).isEqualTo(1);

        JsonNode savedResult = objectMapper.readTree(artifact.getResult());
        JsonNode analysis = savedResult.path("analysis");
        assertThat(savedResult.path("maskingApplied").asBoolean()).isTrue();
        assertThat(savedResult.path("maskedInputSha256").asText()).isEqualTo(expectedMaskedInput.sha256());
        assertThat(savedResult.path("model").asText()).isEqualTo(MODEL);
        assertThat(analysis.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "riskLevel", "summary", "findings", "riskAlerts", "recommendedActions", "dataGaps");
        for (String prohibitedValue : expectedMaskedInput.prohibitedTerms()) {
            assertThat(artifact.getResult()).doesNotContain(prohibitedValue);
        }

        System.out.printf("%n[KYC database live test] workflowId=%s personId=%d workflowStatus=%s agentStatus=%s artifactId=%s%n"
                        + "[KYC database live test] maskedInput=%s%n[KYC database live test] savedAnalysis=%s%n",
                created.workflowId(), PERSON_ID, workflow.getWorkflowStatus(), kycState.getAgentStatus(), artifact.getArtifactId(),
                objectMapper.writeValueAsString(expectedMaskedInput.payload()), analysis);
    }

    @Configuration(proxyBeanMethods = false)
    @MapperScan(basePackages = "com.privatebank.business.mapper")
    @EnableConfigurationProperties(StorageProperties.class)
    @ImportAutoConfiguration({
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            TransactionAutoConfiguration.class,
            MybatisPlusAutoConfiguration.class,
            SpringAiRetryAutoConfiguration.class,
            ToolCallingAutoConfiguration.class,
            DeepSeekChatAutoConfiguration.class
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
        KycModelClient kycModelClient(ChatModel chatModel) {
            return new SpringAiKycModelClient(chatModel);
        }

        @Bean
        KycAnalysisGenerator kycAnalysisGenerator(
                KycModelClient kycModelClient, KycOutputValidator kycOutputValidator, ObjectMapper objectMapper) {
            return new KycAnalysisGenerator(kycModelClient, kycOutputValidator, objectMapper);
        }

        @Bean
        KycDataMaskingService kycDataMaskingService(ObjectMapper objectMapper) {
            return new KycDataMaskingService(objectMapper);
        }

        @Bean
        KycCustomerDataLoader kycCustomerDataLoader(CustomerDataMapper customerDataMapper) {
            return new KycCustomerDataLoader(customerDataMapper);
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
                KycAnalysisGenerator analysisGenerator) {
            return new KycWorkflowExecutionService(stateService, dataLoader, maskingService, analysisGenerator);
        }

        @Bean
        KycAsyncConfiguration kycAsyncConfiguration() {
            return new KycAsyncConfiguration();
        }

        @Bean
        KycWorkflowListener kycWorkflowListener(KycWorkflowExecutionService executionService) {
            return new KycWorkflowListener(executionService);
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
                CurrentUserService currentUserService,
                IdempotencyExecutor idempotencyExecutor,
                WorkflowEventHub eventHub,
                org.springframework.context.ApplicationEventPublisher eventPublisher,
                ObjectMapper objectMapper,
                FileStorageService fileStorageService) {
            return new WorkflowService(
                    workflowStateMapper, agentStateMapper, agentArtifactMapper, reviewMapper, customerDataMapper,
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
