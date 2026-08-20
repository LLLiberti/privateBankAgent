package com.privatebank.agent.application.downstream;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.business.entity.product.ProductMetadata;
import com.privatebank.business.mapper.product.ProductMetadataMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 只读诊断真实产品元数据在哪个 KYC 检索条件下被过滤，不调用大模型、ES 或 Qdrant。
 */
@Tag("live")
@EnabledIfSystemProperty(named = "private-bank.test.live-product-metadata-diagnostic", matches = "true")
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "private-bank.graph.enabled=false"
})
class ProductKnowledgeMetadataDiagnosticLiveTest {

    private static final String DATASOURCE_URL = configuredProperty("spring.datasource.url", "");
    private static final String DATASOURCE_USERNAME = configuredProperty("spring.datasource.username", "");
    private static final String DATASOURCE_PASSWORD = configuredProperty("spring.datasource.password", "");

    private static final List<String> REQUESTED_PRODUCT_IDS = List.of(
            "PR00001", "PR00004", "PR00006", "PR00007", "PR00013", "PR00016", "PR00017");

    private static final List<MetadataProbe> PROBES = List.of(
            new MetadataProbe("00_仅通用产品词", List.of("理财产品")),
            new MetadataProbe("01_稳健型", List.of("稳健型")),
            new MetadataProbe("02_固收类", List.of("固收类")),
            new MetadataProbe("03_低风险", List.of("低风险")),
            new MetadataProbe("04_期限1至2年", List.of("1-2年")),
            new MetadataProbe("05_本金安全", List.of("本金安全")),
            new MetadataProbe("06_确定性收益", List.of("确定性收益")),
            new MetadataProbe(
                    "07_全部条件组合",
                    List.of("稳健型", "固收类", "低风险", "1-2年", "本金安全", "确定性收益")));

    @Autowired
    private ProductMetadataMapper productMetadataMapper;

    @Autowired
    private ProductKnowledgeSearchService productKnowledgeSearchService;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void liveDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DATASOURCE_URL);
        registry.add("spring.datasource.username", () -> DATASOURCE_USERNAME);
        registry.add("spring.datasource.password", () -> DATASOURCE_PASSWORD);
    }

    @Test
    void diagnosesWhichKycConditionRemovesAllProductMetadataCandidates() throws Exception {
        List<ProductMetadata> products = productMetadataMapper.selectList(
                Wrappers.<ProductMetadata>lambdaQuery()
                        .in(ProductMetadata::getProductId, REQUESTED_PRODUCT_IDS)
                        .orderByAsc(ProductMetadata::getProductId));

        assertThat(products)
                .as("数据库中至少应存在一个待诊断产品；若为空，请先检查数据源或产品编号")
                .isNotEmpty();

        Set<String> foundProductIds = new LinkedHashSet<>();
        products.stream().map(ProductMetadata::getProductId).forEach(foundProductIds::add);
        List<String> missingProductIds = REQUESTED_PRODUCT_IDS.stream()
                .filter(productId -> !foundProductIds.contains(productId))
                .toList();

        List<ProductMetadata> activeProducts = products.stream()
                .filter(product -> "ACTIVE".equalsIgnoreCase(product.getProductStatus()))
                .toList();
        List<ProductMetadata> pr2Products = products.stream()
                .filter(product -> "PR2".equalsIgnoreCase(product.getRiskLevel()))
                .toList();
        List<ProductMetadata> hardEligibleProducts = products.stream()
                .filter(product -> "ACTIVE".equalsIgnoreCase(product.getProductStatus()))
                .filter(product -> "PR2".equalsIgnoreCase(product.getRiskLevel()))
                .toList();

        Map<String, Object> hardFilterSummary = new LinkedHashMap<>();
        hardFilterSummary.put("requestedProductIds", REQUESTED_PRODUCT_IDS);
        hardFilterSummary.put("foundProductIds", productIds(products));
        hardFilterSummary.put("missingProductIds", missingProductIds);
        hardFilterSummary.put("activeProductIds", productIds(activeProducts));
        hardFilterSummary.put("pr2ProductIds", productIds(pr2Products));
        hardFilterSummary.put("activePr2ProductIds", productIds(hardEligibleProducts));
        System.out.printf("[PRODUCT_METADATA_DIAGNOSTIC] hardFilter=%s%n",
                objectMapper.writeValueAsString(hardFilterSummary));

        for (ProductMetadata product : products) {
            System.out.printf("[PRODUCT_METADATA_DIAGNOSTIC] metadata=%s%n",
                    objectMapper.writeValueAsString(metadataView(product)));
        }

        for (MetadataProbe probe : PROBES) {
            List<ProductMetadata> matchedProducts = metadataCandidates(hardEligibleProducts, probe.queries());
            Map<String, Object> probeResult = new LinkedHashMap<>();
            probeResult.put("probe", probe.name());
            probeResult.put("queries", probe.queries());
            probeResult.put("beforeProductIds", productIds(hardEligibleProducts));
            probeResult.put("afterProductIds", productIds(matchedProducts));
            probeResult.put("removedProductIds", removedProductIds(hardEligibleProducts, matchedProducts));
            System.out.printf("[PRODUCT_METADATA_DIAGNOSTIC] probe=%s%n",
                    objectMapper.writeValueAsString(probeResult));
        }
    }

    @SuppressWarnings("unchecked")
    private List<ProductMetadata> metadataCandidates(List<ProductMetadata> products, List<String> queries) {
        List<ProductMetadata> result = ReflectionTestUtils.invokeMethod(
                productKnowledgeSearchService,
                "selectMetadataCandidates",
                products,
                queries);
        return result == null ? List.of() : result;
    }

    private List<String> productIds(List<ProductMetadata> products) {
        return products.stream().map(ProductMetadata::getProductId).toList();
    }

    private List<String> removedProductIds(
            List<ProductMetadata> beforeProducts,
            List<ProductMetadata> afterProducts) {
        Set<String> afterProductIds = new LinkedHashSet<>(productIds(afterProducts));
        return beforeProducts.stream()
                .map(ProductMetadata::getProductId)
                .filter(productId -> !afterProductIds.contains(productId))
                .toList();
    }

    private Map<String, Object> metadataView(ProductMetadata product) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("productId", product.getProductId());
        view.put("productName", product.getProductName());
        view.put("productStatus", product.getProductStatus());
        view.put("riskLevel", upper(product.getRiskLevel()));
        view.put("productCategory", product.getProductCategory());
        view.put("incomeType", product.getIncomeType());
        view.put("termDays", product.getTermDays());
        view.put("termType", product.getTermType());
        view.put("targetCustomer", product.getTargetCustomer());
        view.put("eligibilityConditions", product.getEligibilityConditions());
        return view;
    }

    private String upper(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
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
            throw new IllegalStateException("无法读取实时产品元数据诊断配置", exception);
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

    private record MetadataProbe(String name, List<String> queries) {
    }
}
