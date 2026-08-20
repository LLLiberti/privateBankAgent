package com.privatebank.agent.infrastructure.downstream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.agent.application.downstream.ProductExpertAgentExecutor;
import com.privatebank.agent.application.runtime.AgentExecutionRequest;
import com.privatebank.agent.application.runtime.AgentExecutionResult;
import com.privatebank.agent.domain.downstream.KypRecommendationResult;
import com.privatebank.agent.domain.downstream.ProductExpertInput;
import com.privatebank.business.entity.workflow.AgentArtifact;
import com.privatebank.business.enums.workflow.AgentType;
import com.privatebank.business.mapper.workflow.AgentArtifactMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live test for the real product expert (KYP) agent against an existing KYC
 * artifact. It does not create a new workflow; it directly feeds the stored KYC
 * result into {@link ProductExpertAgentExecutor} and lets the agent call the
 * real {@code search_product_knowledge} tool.
 *
 * <p>Run with:
 * <pre>
 * mvn -Dprivate-bank.test.live-product-agent=true -Dtest=ProductExpertLiveTest test
 * </pre>
 */
@Tag("live")
@EnabledIfSystemProperty(named = "private-bank.test.live-product-agent", matches = "true")
@Import(ProductExpertLiveLoggingConfiguration.class)
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "private-bank.graph.enabled=false",
        "private-bank.storage.root=./target/product-expert-live-storage",
        "private-bank.storage.max-file-size-bytes=1048576"
})
class ProductExpertLiveTest {

    private static final List<Map<String, Object>> TOOL_REQUESTS = new CopyOnWriteArrayList<>();
    private static final ThreadLocal<Boolean> SEARCH_TOOL_ACTIVE = ThreadLocal.withInitial(() -> false);

    private static final String WORKFLOW_ID = "WF-352d55f7-0327-48b0-a455-a4341930256a";
    private static final String KYC_ARTIFACT_ID = "ART-3d323c15-87ff-4477-8f4f-daef63446a9d";

    private static final String DATASOURCE_URL = configuredProperty("spring.datasource.url", "");
    private static final String DATASOURCE_USERNAME = configuredProperty("spring.datasource.username", "");
    private static final String DATASOURCE_PASSWORD = configuredProperty("spring.datasource.password", "");
    private static final String ES_URIS = configuredProperty("spring.elasticsearch.uris", "");
    private static final String ES_USERNAME = configuredProperty("spring.elasticsearch.username", "");
    private static final String ES_PASSWORD = configuredProperty("spring.elasticsearch.password", "");
    private static final String QDRANT_HOST = configuredProperty("private-bank.knowledge.qdrant.host", "");
    private static final String QDRANT_PORT = configuredProperty("private-bank.knowledge.qdrant.port", "6333");
    private static final String QDRANT_API_KEY = configuredProperty("private-bank.knowledge.qdrant.api-key", "");
    private static final String QDRANT_COLLECTION = configuredProperty("private-bank.knowledge.qdrant.collection-name", "");
    private static final String EMBEDDING_BASE_URL = configuredProperty("private-bank.knowledge.embedding.base-url", "");
    private static final String EMBEDDING_API_KEY = configuredProperty("private-bank.knowledge.embedding.api-key", "");
    private static final String EMBEDDING_MODEL = configuredProperty("private-bank.knowledge.embedding.model", "");
    private static final String EMBEDDING_DIMENSIONS = configuredProperty(
            "private-bank.knowledge.embedding.dimensions", "1024");
    private static final String DEEPSEEK_API_KEY = configuredProperty("private-bank.agent-runtime.deepseek.api-key", "");
    private static final String DEEPSEEK_BASE_URL = configuredProperty("private-bank.agent-runtime.deepseek.base-url", "");
    private static final String DEEPSEEK_MODEL = configuredProperty("private-bank.agent-runtime.deepseek.model", "");

    @Autowired
    private AgentArtifactMapper artifactMapper;

    @Autowired
    private ProductExpertAgentExecutor productExpertExecutor;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void liveProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DATASOURCE_URL);
        registry.add("spring.datasource.username", () -> DATASOURCE_USERNAME);
        registry.add("spring.datasource.password", () -> DATASOURCE_PASSWORD);
        registry.add("spring.flyway.enabled", () -> false);
        registry.add("private-bank.graph.enabled", () -> false);
        registry.add("spring.elasticsearch.uris", () -> ES_URIS);
        registry.add("spring.elasticsearch.username", () -> ES_USERNAME);
        registry.add("spring.elasticsearch.password", () -> ES_PASSWORD);
        registry.add("private-bank.knowledge.qdrant.host", () -> QDRANT_HOST);
        registry.add("private-bank.knowledge.qdrant.port", () -> QDRANT_PORT);
        registry.add("private-bank.knowledge.qdrant.api-key", () -> QDRANT_API_KEY);
        registry.add("private-bank.knowledge.qdrant.collection-name", () -> QDRANT_COLLECTION);
        registry.add("private-bank.knowledge.embedding.base-url", () -> EMBEDDING_BASE_URL);
        registry.add("private-bank.knowledge.embedding.api-key", () -> EMBEDDING_API_KEY);
        registry.add("private-bank.knowledge.embedding.model", () -> EMBEDDING_MODEL);
        registry.add("private-bank.knowledge.embedding.dimensions", () -> EMBEDDING_DIMENSIONS);
        registry.add("private-bank.agent-runtime.deepseek.api-key", () -> DEEPSEEK_API_KEY);
        registry.add("private-bank.agent-runtime.deepseek.base-url", () -> DEEPSEEK_BASE_URL);
        registry.add("private-bank.agent-runtime.deepseek.model", () -> DEEPSEEK_MODEL);
    }

    @BeforeAll
    static void requireLiveConfiguration() {
        Assertions.assertFalse(DATASOURCE_URL.isBlank(), "main application.yml must configure the live database URL");
        Assertions.assertFalse(DEEPSEEK_API_KEY.isBlank(), "main application.yml must configure the live model API key");
    }

    @BeforeEach
    void clearCapturedToolRequests() {
        ProductExpertLiveLoggingConfiguration.clearCapturedLogs();
    }

    @Test
    void runsRealProductExpertAgainstExistingKycArtifact() throws Exception {
        AgentArtifact kyc = artifactMapper.selectById(KYC_ARTIFACT_ID);
        assertThat(kyc)
                .as("KYC artifact %s must exist in the configured database", KYC_ARTIFACT_ID)
                .isNotNull();
        assertThat(kyc.getAgentType()).isEqualTo(AgentType.CUSTOMER_INSIGHT);
        assertThat(kyc.getResult()).isNotBlank();

        JsonNode kycJson = objectMapper.readTree(kyc.getResult());
        assertThat(kycJson.path("contractVersion").asText()).isEqualTo("kyc-result.v2");

        assertDeepSeekHostResolvable();

        ProductExpertInput input = new ProductExpertInput(
                WORKFLOW_ID,
                KYC_ARTIFACT_ID,
                kyc.getResult(),
                List.of(),
                List.of());

        AgentExecutionRequest<ProductExpertInput> request = new AgentExecutionRequest<>(
                WORKFLOW_ID,
                "EXE-LIVE-PRODUCT-" + UUID.randomUUID(),
                AgentType.PRODUCT_EXPERT,
                "USER-LIVE-PRODUCT",
                input,
                Map.of("kycArtifactId", KYC_ARTIFACT_ID));

        long startedNanos = System.nanoTime();
        AgentExecutionResult<KypRecommendationResult> result = productExpertExecutor.execute(request);
        long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000;

        assertThat(ProductExpertLiveLoggingConfiguration.capturedToolRequests())
                .as("产品专家必须真实调用 search_product_knowledge，并输出本次请求参数")
                .isNotEmpty();
        KypRecommendationResult output = result.output();
        assertThat(output).isNotNull();
        assertThat(output.mode()).isNotBlank();
        assertThat(output.customerId()).isNotBlank();
        assertThat(output.kycArtifactRef()).isEqualTo(KYC_ARTIFACT_ID);
        assertThat(output.recommendedItems()).isNotNull();
        assertThat(output.rejectedItems()).isNotNull();
        assertThat(output.reviewRequiredItems()).isNotNull();
        assertThat(output.ruleCheckResults()).isNotNull();
        assertThat(output.unresolvedItems()).isNotNull();
        assertThat(output.productEvidenceRefs()).isNotNull();
        assertThat(output.recommendedItems())
                .as("该真实 KYC 必须至少生成一个有证据、带限制披露的产品推荐")
                .isNotEmpty();
        assertThat(output.productEvidenceRefs())
                .as("真实产品推荐必须召回产品知识证据")
                .isNotEmpty();
        assertThat(output.recommendedItems()).allSatisfy(item -> {
            assertThat(item.limitations()).isNotEmpty();
            assertThat(item.evidenceRefs()).isNotEmpty();
        });

        System.out.printf(
                "[PRODUCT_EXPERT_LIVE] workflowId=%s kycArtifactId=%s elapsedMs=%d model=%s recommended=%d rejected=%d review=%d unresolved=%d evidence=%d%n",
                WORKFLOW_ID,
                KYC_ARTIFACT_ID,
                elapsedMs,
                result.modelName(),
                output.recommendedItems().size(),
                output.rejectedItems().size(),
                output.reviewRequiredItems().size(),
                output.unresolvedItems().size(),
                output.productEvidenceRefs().size());
        System.out.printf("[PRODUCT_EXPERT_LIVE] result=%s%n", objectMapper.writeValueAsString(output));
    }

    private static void assertDeepSeekHostResolvable() {
        if (isProxyConfigured()) {
            return;
        }
        try {
            URI uri = URI.create(DEEPSEEK_BASE_URL);
            InetAddress.getByName(uri.getHost());
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "无法解析 DeepSeek API 域名: " + DEEPSEEK_BASE_URL
                            + "，请检查网络/DNS/代理，或通过 PRIVATE_BANK_DEEPSEEK_BASE_URL 指定可访问的 API 地址",
                    exception);
        }
    }

    private static boolean isProxyConfigured() {
        return System.getProperty("https.proxyHost") != null
                || System.getenv("HTTPS_PROXY") != null
                || System.getenv("https_proxy") != null
                || System.getenv("ALL_PROXY") != null
                || System.getenv("all_proxy") != null;
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
            throw new IllegalStateException("Unable to read live product expert test configuration", exception);
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
