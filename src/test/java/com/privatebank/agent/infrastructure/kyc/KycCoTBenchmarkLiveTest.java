package com.privatebank.agent.infrastructure.kyc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.agent.application.kyc.KycAgentExecutor;
import com.privatebank.agent.application.kyc.KycOutputValidator;
import com.privatebank.agent.application.runtime.AgentExecutionRequest;
import com.privatebank.agent.application.runtime.AgentExecutionResult;
import com.privatebank.agent.config.AgentScopeConfiguration;
import com.privatebank.agent.config.AgentScopeProperties;
import com.privatebank.agent.domain.kyc.KycGenerationException;
import com.privatebank.agent.domain.kyc.KycMaskedInput;
import com.privatebank.agent.domain.kyc.KycStructuredResult;
import com.privatebank.agent.infrastructure.agentscope.AgentRuntimeContextFactory;
import com.privatebank.agent.infrastructure.agentscope.AgentScopeExecutionEngine;
import com.privatebank.business.enums.workflow.AgentType;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Manual live benchmark comparing the default KYC prompt with a CoT-augmented
 * prompt. Run with:
 *
 * <pre>
 * mvn -Dprivate-bank.test.live-cot-benchmark=true -Dtest=KycCoTBenchmarkLiveTest test
 * </pre>
 *
 * Outputs are written under target/cot-benchmark/ for later human scoring.
 */
@Tag("live")
@EnabledIfSystemProperty(named = "private-bank.test.live-cot-benchmark", matches = "true")
class KycCoTBenchmarkLiveTest {

    private static final String OUTPUT_DIR = "target/cot-benchmark";

    private static final String API_KEY = configuredProperty("private-bank.agent-runtime.deepseek.api-key", "");
    private static final String BASE_URL = configuredProperty(
            "private-bank.agent-runtime.deepseek.base-url", "https://api.deepseek.com/v1");
    private static final String MODEL = configuredProperty(
            "private-bank.agent-runtime.deepseek.model", "deepseek-v4-flash");

    @Test
    void compareBaselineAndCoTPrompts() throws Exception {
        Assumptions.assumeTrue(!API_KEY.isBlank(), "请配置 PRIVATE_BANK_DEEPSEEK_API_KEY");

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        AgentScopeProperties properties = new AgentScopeProperties(
                new AgentScopeProperties.DeepSeek(BASE_URL, API_KEY, MODEL, 0D), 2, 4, 2);
        Model model = new AgentScopeConfiguration().privateBankAgentModel(properties);
        AgentScopeExecutionEngine engine = new AgentScopeExecutionEngine(
                model, properties, new AgentRuntimeContextFactory(), ignored -> { });
        KycOutputValidator validator = new KycOutputValidator(objectMapper);

        String baselinePrompt = KycAgentExecutor.SYSTEM_PROMPT;
        String cotPrompt = baselinePrompt + """
                
                请按以下步骤逐步分析，但不要把这些步骤输出到最终 JSON 中：
                1. 先列出输入中所有可用事实及对应 SRC-* 证据；
                2. 按 PERSON、ENTERPRISE、FAMILY、SOCIAL 分别归纳风险信号；
                3. 将图谱关系与结构化事实交叉验证，判断是新增、印证还是无增量；
                4. 识别数据缺口和待核验项；
                5. 最后严格按照 KycStructuredResult 字段格式输出 JSON。
                """;

        KycAgentExecutor baselineExecutor = new KycAgentExecutor(
                engine, validator, objectMapper, properties, baselinePrompt);
        KycAgentExecutor cotExecutor = new KycAgentExecutor(
                engine, validator, objectMapper, properties, cotPrompt);

        List<BenchmarkSample> samples = samples();
        List<VariantResult> results = new ArrayList<>();

        Path outputRoot = Path.of(OUTPUT_DIR);
        Files.createDirectories(outputRoot);

        for (Variant variant : List.of(
                new Variant("baseline", baselineExecutor),
                new Variant("cot", cotExecutor))) {
            Path variantDir = outputRoot.resolve(variant.name());
            Files.createDirectories(variantDir);

            VariantMetrics metrics = new VariantMetrics(samples.size());
            for (int i = 0; i < samples.size(); i++) {
                BenchmarkSample sample = samples.get(i);
                SampleOutcome outcome = runSample(variant.executor(), sample.input(), objectMapper);
                outcome.writeTo(variantDir.resolve("sample-" + (i + 1) + ".json"), objectMapper);
                metrics.add(outcome);
                results.add(new VariantResult(variant.name(), sample.name(), outcome));
            }

            Path summaryPath = variantDir.resolve("summary.json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(summaryPath.toFile(), metrics.toMap());
            System.out.printf("[KYC CoT benchmark] %s metrics=%s%n", variant.name(), metrics.toMap());
        }

        Path allPath = outputRoot.resolve("all-results.json");
        List<Map<String, Object>> resultMaps = results.stream().map(result -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("variant", result.variant());
            map.put("sample", result.sample());
            map.put("success", result.outcome().success);
            map.put("attempts", result.outcome().attempts);
            map.put("modelName", result.outcome().modelName);
            map.put("error", result.outcome().error);
            map.put("output", result.outcome().outputJson);
            return map;
        }).toList();
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(allPath.toFile(), resultMaps);

        System.out.println("[KYC CoT benchmark] outputs written to " + outputRoot.toAbsolutePath());
        assertThat(Files.exists(outputRoot)).isTrue();
    }

    private SampleOutcome runSample(
            KycAgentExecutor executor,
            KycMaskedInput input,
            ObjectMapper objectMapper) {
        try {
            AgentExecutionResult<KycStructuredResult> result = executor.execute(
                    new AgentExecutionRequest<>(
                            "BENCH-KYC",
                            "EXE-" + System.nanoTime(),
                            AgentType.CUSTOMER_INSIGHT,
                            "BENCH",
                            input,
                            Map.of()));
            return SampleOutcome.success(
                    result.attempts(),
                    result.modelName(),
                    serialize(result.output(), objectMapper));
        } catch (KycGenerationException exception) {
            return SampleOutcome.failure(exception.getMessage());
        } catch (RuntimeException exception) {
            return SampleOutcome.failure(exception.getMessage());
        }
    }

    private static String serialize(Object value, ObjectMapper objectMapper) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException exception) {
            throw new IllegalStateException("无法序列化 KYC 结果", exception);
        }
    }

    private List<BenchmarkSample> samples() {
        Map<String, Object> basicPayload = new LinkedHashMap<>();
        basicPayload.put("contractVersion", "kyc-input.v1");
        basicPayload.put("person", Map.of(
                "customer", Map.of("personType", "ENTREPRENEUR", "riskLevel", "MEDIUM"),
                "financialFacts", List.of(Map.of(
                        "factCategory", "ASSET",
                        "amount", 1000000,
                        "currencyCode", "CNY",
                        "sourceRef", "SRC-1"))));
        basicPayload.put("enterprise", Map.of(
                "relations", List.of(Map.of(
                        "relationType", "CONTROLLER",
                        "industryName", "MANUFACTURING",
                        "sourceRef", "SRC-2"))));
        basicPayload.put("family", Map.of("members", List.of()));
        basicPayload.put("social", Map.of("relations", List.of()));

        Map<String, Object> graphPayload = new LinkedHashMap<>(basicPayload);
        graphPayload.put("relationshipGraph", Map.of(
                "available", true,
                "relationshipCount", 1,
                "evidenceRefs", List.of("SRC-2"),
                "relationships", List.of(Map.of(
                        "sourceRef", "SRC-2",
                        "relationType", "CONTROLS"))));

        Map<String, Object> supplementPayload = new LinkedHashMap<>(basicPayload);
        supplementPayload.put("managerSupplement", Map.of(
                "signals", List.of("LIQUIDITY_NEED")));

        return List.of(
                new BenchmarkSample("basic", new KycMaskedInput(
                        basicPayload,
                        Map.of("SRC-1", 1001L, "SRC-2", 1002L),
                        Set.of("张三", "测试企业"),
                        Map.of("P-1", "张三"),
                        "0".repeat(64))),
                new BenchmarkSample("with-graph", new KycMaskedInput(
                        graphPayload,
                        Map.of("SRC-1", 1001L, "SRC-2", 1002L),
                        Set.of("张三", "测试企业"),
                        Map.of("P-1", "张三"),
                        "1".repeat(64))),
                new BenchmarkSample("with-supplement", new KycMaskedInput(
                        supplementPayload,
                        Map.of("SRC-1", 1001L, "SRC-2", 1002L),
                        Set.of("张三", "测试企业"),
                        Map.of("P-1", "张三"),
                        "2".repeat(64))));
    }

    private record BenchmarkSample(String name, KycMaskedInput input) {
    }

    private record Variant(String name, KycAgentExecutor executor) {
    }

    private record VariantResult(String variant, String sample, SampleOutcome outcome) {
    }

    private static final class SampleOutcome {
        private final boolean success;
        private final int attempts;
        private final String modelName;
        private final String error;
        private final String outputJson;

        private SampleOutcome(boolean success, int attempts, String modelName, String error, String outputJson) {
            this.success = success;
            this.attempts = attempts;
            this.modelName = modelName;
            this.error = error;
            this.outputJson = outputJson;
        }

        static SampleOutcome success(int attempts, String modelName, String outputJson) {
            return new SampleOutcome(true, attempts, modelName, null, outputJson);
        }

        static SampleOutcome failure(String error) {
            return new SampleOutcome(false, 0, null, error, null);
        }

        void writeTo(Path path, ObjectMapper objectMapper) throws IOException {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("success", success);
            map.put("attempts", attempts);
            map.put("modelName", modelName);
            map.put("error", error);
            map.put("output", outputJson == null ? null : objectMapper.readTree(outputJson));
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), map);
        }
    }

    private static final class VariantMetrics {
        private final int totalSamples;
        private int successCount;
        private int firstPassCount;
        private int totalAttempts;
        private int totalFindings;
        private int totalEvidenceRefs;
        private int samplesWithDataGaps;
        private final List<String> failures = new ArrayList<>();

        private VariantMetrics(int totalSamples) {
            this.totalSamples = totalSamples;
        }

        void add(SampleOutcome outcome) {
            if (!outcome.success) {
                failures.add(outcome.error);
                return;
            }
            successCount++;
            totalAttempts += outcome.attempts;
            if (outcome.attempts == 1) {
                firstPassCount++;
            }
            try {
                var root = new ObjectMapper().readTree(outcome.outputJson);
                int findings = root.path("findings").size();
                int evidenceRefs = 0;
                for (var finding : root.path("findings")) {
                    evidenceRefs += finding.path("evidenceRefs").size();
                }
                totalFindings += findings;
                totalEvidenceRefs += evidenceRefs;
                if (root.path("dataGaps").size() > 0) {
                    samplesWithDataGaps++;
                }
            } catch (IOException ignored) {
                // Should not happen because the output was already validated.
            }
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("totalSamples", totalSamples);
            map.put("successCount", successCount);
            map.put("firstPassCount", firstPassCount);
            map.put("finalPassRate", totalSamples == 0 ? 0D : successCount * 100.0 / totalSamples);
            map.put("firstPassRate", totalSamples == 0 ? 0D : firstPassCount * 100.0 / totalSamples);
            map.put("avgAttempts", successCount == 0 ? 0D : totalAttempts * 1.0 / successCount);
            map.put("avgFindings", successCount == 0 ? 0D : totalFindings * 1.0 / successCount);
            map.put("avgEvidenceRefsPerFinding", totalFindings == 0 ? 0D : totalEvidenceRefs * 1.0 / totalFindings);
            map.put("samplesWithDataGaps", samplesWithDataGaps);
            map.put("failures", failures);
            return map;
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
            throw new IllegalStateException("无法读取真实 KYC 测试配置", exception);
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
