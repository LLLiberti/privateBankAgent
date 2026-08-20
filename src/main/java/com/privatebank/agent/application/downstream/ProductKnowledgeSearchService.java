package com.privatebank.agent.application.downstream;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.agent.domain.downstream.ProductKnowledgeEvidence;
import com.privatebank.agent.domain.downstream.ProductKnowledgeSearchResult;
import com.privatebank.agent.domain.downstream.ProductRetrievalIssue;
import com.privatebank.business.config.ProductKnowledgeProperties;
import com.privatebank.business.entity.product.ProductMetadata;
import com.privatebank.business.mapper.product.ProductMetadataMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Product knowledge search used by KYP Agent.
 *
 * <p>MySQL first applies hard business constraints, then product metadata builds
 * the candidate set in memory. Elasticsearch and Qdrant recall evidence only
 * inside the candidate document scope.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductKnowledgeSearchService {

    private static final String ACTIVE = "ACTIVE";
    private static final Set<String> GENERIC_PRODUCT_TERMS = Set.of("产品", "理财", "理财产品", "产品知识");
    private static final Set<String> SUITABILITY_TERMS = Set.of("保守型", "稳健型", "平衡型", "成长型", "进取型");
    private static final Pattern YEAR_RANGE_PATTERN = Pattern.compile("^(\\d+)\\s*[-~—至到]\\s*(\\d+)\\s*年(?:期)?$");

    private final ProductMetadataMapper productMetadataMapper;
    private final ProductDocumentIdResolver documentIdResolver;
    private final ProductKnowledgeProperties properties;
    private final ObjectMapper objectMapper;

    @Value("${spring.elasticsearch.uris:}")
    private String esUris;

    @Value("${spring.elasticsearch.username:}")
    private String esUsername;

    @Value("${spring.elasticsearch.password:}")
    private String esPassword;

    public ProductKnowledgeSearchResult search(
            List<String> queries,
            List<String> requestedProductIds,
            String riskLevel,
            String saleStatus) {
        List<ProductRetrievalIssue> issues = new ArrayList<>();
        List<String> effectiveQueries = normalizeValues(queries);
        if (effectiveQueries.isEmpty()) {
            issues.add(issue("REQUEST", "EMPTY_QUERY", "未提供可用于产品知识检索的关键词", List.of()));
            return new ProductKnowledgeSearchResult(List.of(), List.of(), issues);
        }

        List<ProductMetadata> eligibleProducts = loadHardEligibleProducts(
                normalizeValues(requestedProductIds), riskLevel, saleStatus);
        if (eligibleProducts.isEmpty()) {
            issues.add(issue("MYSQL", "NO_ELIGIBLE_PRODUCT", "没有产品满足销售状态、产品风险等级或指定产品范围", List.of()));
            return new ProductKnowledgeSearchResult(List.of(), List.of(), issues);
        }

        List<String> eligibleProductIds = eligibleProducts.stream()
                .map(ProductMetadata::getProductId)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        List<ProductMetadata> matchedProducts = selectMetadataCandidates(eligibleProducts, effectiveQueries);
        if (matchedProducts.isEmpty()) {
            issues.add(issue(
                    "MYSQL_METADATA",
                    "NO_METADATA_MATCH",
                    "合规产品中没有产品元数据与检索条件匹配",
                    eligibleProductIds));
            return new ProductKnowledgeSearchResult(List.of(), List.of(), issues);
        }

        List<String> matchedProductIds = matchedProducts.stream()
                .map(ProductMetadata::getProductId)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        ProductDocumentIdResolver.Resolution resolution = documentIdResolver.resolve(matchedProductIds);
        if (!resolution.unmappedProductIds().isEmpty()) {
            issues.add(issue(
                    "DOCUMENT_MAPPING",
                    "MISSING_DOCUMENT_MAPPING",
                    "部分合规产品没有关联产品知识文档",
                    resolution.unmappedProductIds()));
        }
        if (resolution.documentIds().isEmpty()) {
            issues.add(issue(
                    "DOCUMENT_MAPPING",
                    "NO_PRODUCT_DOCUMENT",
                    "合规产品没有可检索的产品知识文档",
                    matchedProductIds));
            return new ProductKnowledgeSearchResult(List.of(), List.of(), issues);
        }

        String semanticQuery = String.join(" ", effectiveQueries);
        List<ProductKnowledgeEvidence> esEvidence = esSearch(
                semanticQuery, resolution.documentIds(), resolution.productIdByDocumentId(), matchedProductIds, issues);
        List<ProductKnowledgeEvidence> vectorEvidence = qdrantSearch(
                semanticQuery, resolution.documentIds(), resolution.productIdByDocumentId(), matchedProductIds, issues);
        List<ProductKnowledgeEvidence> merged = mergeAndRerank(
                esEvidence, vectorEvidence, properties.topK());

        if (merged.isEmpty()) {
            issues.add(issue(
                    "RETRIEVAL",
                    "NO_PRODUCT_EVIDENCE",
                    "合规产品范围内未召回任何产品知识证据",
                    matchedProductIds));
            return new ProductKnowledgeSearchResult(List.of(), List.of(), issues);
        }

        List<String> candidateProductIds = merged.stream()
                .map(ProductKnowledgeEvidence::productId)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        return new ProductKnowledgeSearchResult(candidateProductIds, merged, issues);
    }

    private List<ProductMetadata> loadHardEligibleProducts(
            List<String> requestedProductIds,
            String riskLevel,
            String saleStatus) {
        String effectiveSaleStatus = StringUtils.hasText(saleStatus) ? saleStatus.trim() : ACTIVE;
        var wrapper = Wrappers.<ProductMetadata>lambdaQuery()
                .eq(ProductMetadata::getProductStatus, effectiveSaleStatus)
                .eq(StringUtils.hasText(riskLevel), ProductMetadata::getRiskLevel, trimToNull(riskLevel))
                .in(!requestedProductIds.isEmpty(), ProductMetadata::getProductId, requestedProductIds)
                .orderByAsc(ProductMetadata::getProductId);
        return productMetadataMapper.selectList(wrapper);
    }

    private List<ProductMetadata> selectMetadataCandidates(
            List<ProductMetadata> eligibleProducts,
            List<String> queries) {
        List<String> terms = metadataTerms(queries);
        if (terms.isEmpty()) {
            return eligibleProducts;
        }

        List<MetadataProductMatch> matches = eligibleProducts.stream()
                .map(product -> metadataMatch(product, terms))
                .toList();
        boolean containsMetadataCondition = matches.stream()
                .anyMatch(match -> match.recognizedCount() > 0);
        if (!containsMetadataCondition) {
            return eligibleProducts;
        }

        return matches.stream()
                .filter(match -> !match.contradicted() && match.score() > 0)
                .sorted(Comparator.comparingInt(MetadataProductMatch::score).reversed()
                        .thenComparing(match -> nullToEmpty(match.product().getProductId())))
                .map(MetadataProductMatch::product)
                .toList();
    }

    private MetadataProductMatch metadataMatch(ProductMetadata product, List<String> terms) {
        int recognizedCount = 0;
        int score = 0;
        boolean contradicted = false;
        for (String term : terms) {
            MetadataTermMatch match = metadataTermMatch(product, term);
            if (match.recognized()) {
                recognizedCount++;
            }
            if (match.matched()) {
                score++;
            }
            contradicted = contradicted || match.contradicted();
        }
        return new MetadataProductMatch(product, recognizedCount, score, contradicted);
    }

    private MetadataTermMatch metadataTermMatch(ProductMetadata product, String term) {
        if (GENERIC_PRODUCT_TERMS.contains(term)) {
            return MetadataTermMatch.ignored();
        }

        Matcher yearRange = YEAR_RANGE_PATTERN.matcher(term);
        if (yearRange.matches()) {
            int minimumDays = Integer.parseInt(yearRange.group(1)) * 365;
            int maximumDays = Integer.parseInt(yearRange.group(2)) * 365;
            Integer termDays = product.getTermDays();
            return MetadataTermMatch.recognized(
                    termDays != null && termDays >= minimumDays && termDays <= maximumDays);
        }

        if (SUITABILITY_TERMS.contains(term)) {
            return MetadataTermMatch.recognized(contains(product.getEligibilityConditions(), term));
        }
        if ("低风险".equals(term)) {
            return MetadataTermMatch.recognized(Set.of("PR1", "PR2").contains(upper(product.getRiskLevel())));
        }
        if ("中低风险".equals(term)) {
            return MetadataTermMatch.recognized(Set.of("PR1", "PR2", "PR3").contains(upper(product.getRiskLevel())));
        }
        if ("固收".equals(term) || "固收类".equals(term)) {
            return MetadataTermMatch.recognized(
                    contains(product.getProductCategory(), "固定收益")
                            || contains(product.getProductCategory(), "固收"));
        }
        if ("本金安全".equals(term) || "保本".equals(term)) {
            String incomeType = nullToEmpty(product.getIncomeType());
            boolean nonPrincipalProtected = incomeType.contains("非保本");
            boolean principalProtected = !nonPrincipalProtected && incomeType.contains("保本");
            return new MetadataTermMatch(true, principalProtected, nonPrincipalProtected);
        }
        if ("确定性收益".equals(term) || "确定收益".equals(term)) {
            String incomeType = nullToEmpty(product.getIncomeType());
            boolean floating = incomeType.contains("浮动");
            boolean deterministic = !floating
                    && (incomeType.contains("固定") || incomeType.contains("保证") || incomeType.contains("确定"));
            return new MetadataTermMatch(true, deterministic, floating);
        }

        boolean directMatch = metadataText(product).contains(term);
        return directMatch ? MetadataTermMatch.recognized(true) : MetadataTermMatch.ignored();
    }

    private List<String> metadataTerms(List<String> queries) {
        Set<String> terms = new LinkedHashSet<>();
        for (String query : queries) {
            for (String term : query.toLowerCase(Locale.ROOT).split("[\\s,，;；、|/]+")) {
                if (StringUtils.hasText(term)) {
                    terms.add(term.trim());
                }
            }
        }
        return List.copyOf(terms);
    }

    private String metadataText(ProductMetadata product) {
        return String.join("\n",
                        nullToEmpty(product.getProductName()),
                        nullToEmpty(product.getProductCategory()),
                        nullToEmpty(product.getIncomeType()),
                        nullToEmpty(product.getOperationMode()),
                        nullToEmpty(product.getTermType()),
                        nullToEmpty(product.getLiquidityRule()),
                        nullToEmpty(product.getTargetCustomer()),
                        nullToEmpty(product.getEligibilityConditions()))
                .toLowerCase(Locale.ROOT);
    }

    private boolean contains(String value, String expected) {
        return nullToEmpty(value).toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT));
    }

    private String upper(String value) {
        return nullToEmpty(value).trim().toUpperCase(Locale.ROOT);
    }

    private List<ProductKnowledgeEvidence> esSearch(
            String query,
            List<String> documentIds,
            Map<String, String> productIdByDocumentId,
            List<String> affectedProductIds,
            List<ProductRetrievalIssue> issues) {
        if (!StringUtils.hasText(esUris)) {
            issues.add(issue("ELASTICSEARCH", "ELASTICSEARCH_NOT_CONFIGURED",
                    "Elasticsearch 产品知识检索未配置", affectedProductIds));
            return List.of();
        }
        try {
            RestClient client = RestClient.builder()
                    .baseUrl(normalizeBase(esUris))
                    .defaultHeaders(headers -> {
                        if (StringUtils.hasText(esUsername) && StringUtils.hasText(esPassword)) {
                            headers.setBasicAuth(esUsername, esPassword);
                        }
                        headers.setContentType(MediaType.APPLICATION_JSON);
                    })
                    .build();
            Map<String, Object> body = Map.of(
                    "size", recallSize(),
                    "query", Map.of(
                            "bool", Map.of(
                                    "must", List.of(Map.of("match", Map.of("content", query))),
                                    "filter", List.of(Map.of("terms", Map.of("document_id", documentIds))))));
            String response = client.post()
                    .uri("/" + properties.elasticsearch().index() + "/_search")
                    .body(body)
                    .retrieve()
                    .body(String.class);
            int issueCount = issues.size();
            List<ProductKnowledgeEvidence> evidence = parseEsResponse(
                    response, productIdByDocumentId, affectedProductIds, issues);
            if (evidence.isEmpty() && issues.size() == issueCount) {
                issues.add(issue("ELASTICSEARCH", "ELASTICSEARCH_NO_HITS",
                        "Elasticsearch 在合规产品范围内没有召回证据", affectedProductIds));
            }
            return evidence;
        } catch (Exception exception) {
            log.warn("Elasticsearch product knowledge search failed", exception);
            issues.add(issue("ELASTICSEARCH", "ELASTICSEARCH_REQUEST_FAILED",
                    "Elasticsearch 产品知识检索请求失败", affectedProductIds));
            return List.of();
        }
    }

    private List<ProductKnowledgeEvidence> qdrantSearch(
            String query,
            List<String> documentIds,
            Map<String, String> productIdByDocumentId,
            List<String> affectedProductIds,
            List<ProductRetrievalIssue> issues) {
        ProductKnowledgeProperties.Qdrant qdrant = properties.qdrant();
        if (!StringUtils.hasText(qdrant.host())) {
            issues.add(issue("QDRANT", "QDRANT_NOT_CONFIGURED",
                    "Qdrant 产品知识检索未配置", affectedProductIds));
            return List.of();
        }

        List<Double> vector = embed(query, affectedProductIds, issues);
        if (vector.isEmpty()) {
            return List.of();
        }

        try {
            RestClient client = RestClient.builder()
                    .baseUrl("http://" + qdrant.host() + ":" + qdrant.port())
                    .defaultHeaders(headers -> {
                        if (StringUtils.hasText(qdrant.apiKey())) {
                            headers.setBearerAuth(qdrant.apiKey());
                        }
                        headers.setContentType(MediaType.APPLICATION_JSON);
                    })
                    .build();
            Map<String, Object> body = Map.of(
                    "vector", vector,
                    "limit", recallSize(),
                    "with_payload", true,
                    "filter", Map.of("must", List.of(
                            Map.of("key", "document_id", "match", Map.of("any", documentIds)))));
            String response = client.post()
                    .uri("/collections/" + qdrant.collectionName() + "/points/search")
                    .body(body)
                    .retrieve()
                    .body(String.class);
            int issueCount = issues.size();
            List<ProductKnowledgeEvidence> evidence = parseQdrantResponse(
                    response, productIdByDocumentId, affectedProductIds, issues);
            if (evidence.isEmpty() && issues.size() == issueCount) {
                issues.add(issue("QDRANT", "QDRANT_NO_HITS",
                        "Qdrant 在合规产品范围内没有召回证据", affectedProductIds));
            }
            return evidence;
        } catch (Exception exception) {
            log.warn("Qdrant product knowledge search failed", exception);
            issues.add(issue("QDRANT", "QDRANT_REQUEST_FAILED",
                    "Qdrant 产品知识检索请求失败", affectedProductIds));
            return List.of();
        }
    }

    private List<Double> embed(
            String text,
            List<String> affectedProductIds,
            List<ProductRetrievalIssue> issues) {
        ProductKnowledgeProperties.Embedding embedding = properties.embedding();
        if (!StringUtils.hasText(embedding.baseUrl())
                || !StringUtils.hasText(embedding.apiKey())
                || !StringUtils.hasText(embedding.model())) {
            issues.add(issue("EMBEDDING", "EMBEDDING_NOT_CONFIGURED",
                    "查询向量模型未完整配置", affectedProductIds));
            return List.of();
        }

        try {
            RestClient client = RestClient.builder()
                    .baseUrl(normalizeBase(embedding.baseUrl()))
                    .defaultHeaders(headers -> {
                        headers.setBearerAuth(embedding.apiKey());
                        headers.setContentType(MediaType.APPLICATION_JSON);
                    })
                    .build();
            String response = client.post()
                    .uri("/embeddings")
                    .body(Map.of(
                            "model", embedding.model(),
                            "input", List.of(text),
                            "dimensions", embedding.dimensions(),
                            "encoding_format", "float"))
                    .retrieve()
                    .body(String.class);
            JsonNode data = objectMapper.readTree(response).path("data");
            if (!data.isArray() || data.isEmpty() || !data.get(0).path("embedding").isArray()) {
                issues.add(issue("EMBEDDING", "EMBEDDING_INVALID_RESPONSE",
                        "查询向量模型没有返回有效向量", affectedProductIds));
                return List.of();
            }
            List<Double> vector = new ArrayList<>();
            for (JsonNode value : data.get(0).path("embedding")) {
                vector.add(value.asDouble());
            }
            if (vector.size() != properties.embedding().dimensions()) {
                issues.add(issue("EMBEDDING", "EMBEDDING_DIMENSION_MISMATCH",
                        "查询向量维度与产品知识库配置不一致", affectedProductIds));
                return List.of();
            }
            return vector;
        } catch (Exception exception) {
            log.warn("Product query embedding failed", exception);
            issues.add(issue("EMBEDDING", "EMBEDDING_REQUEST_FAILED",
                    "查询向量生成失败", affectedProductIds));
            return List.of();
        }
    }

    private List<ProductKnowledgeEvidence> parseEsResponse(
            String response,
            Map<String, String> productIdByDocumentId,
            List<String> affectedProductIds,
            List<ProductRetrievalIssue> issues) {
        try {
            JsonNode hits = objectMapper.readTree(response).path("hits").path("hits");
            if (!hits.isArray()) {
                throw new IllegalArgumentException("hits.hits is not an array");
            }
            List<ProductKnowledgeEvidence> result = new ArrayList<>();
            int rank = 1;
            for (JsonNode hit : hits) {
                JsonNode source = hit.path("_source");
                ProductKnowledgeEvidence evidence = toEvidence(source, productIdByDocumentId, rank);
                if (evidence != null) {
                    result.add(evidence);
                    rank++;
                }
            }
            return result;
        } catch (Exception exception) {
            log.warn("Elasticsearch product knowledge response parse failed", exception);
            issues.add(issue("ELASTICSEARCH", "ELASTICSEARCH_INVALID_RESPONSE",
                    "Elasticsearch 返回了无法解析的产品知识结果", affectedProductIds));
            return List.of();
        }
    }

    private List<ProductKnowledgeEvidence> parseQdrantResponse(
            String response,
            Map<String, String> productIdByDocumentId,
            List<String> affectedProductIds,
            List<ProductRetrievalIssue> issues) {
        try {
            JsonNode result = objectMapper.readTree(response).path("result");
            if (!result.isArray()) {
                throw new IllegalArgumentException("result is not an array");
            }
            List<ProductKnowledgeEvidence> evidence = new ArrayList<>();
            int rank = 1;
            for (JsonNode point : result) {
                ProductKnowledgeEvidence item = toEvidence(
                        point.path("payload"), productIdByDocumentId, rank);
                if (item != null) {
                    evidence.add(item);
                    rank++;
                }
            }
            return evidence;
        } catch (Exception exception) {
            log.warn("Qdrant product knowledge response parse failed", exception);
            issues.add(issue("QDRANT", "QDRANT_INVALID_RESPONSE",
                    "Qdrant 返回了无法解析的产品知识结果", affectedProductIds));
            return List.of();
        }
    }

    private ProductKnowledgeEvidence toEvidence(
            JsonNode source,
            Map<String, String> productIdByDocumentId,
            int rank) {
        String chunkId = source.path("chunk_id").asText();
        String documentId = source.path("document_id").asText();
        String productId = productIdByDocumentId.get(documentId);
        if (!StringUtils.hasText(chunkId) || !StringUtils.hasText(documentId) || !StringUtils.hasText(productId)) {
            return null;
        }
        return new ProductKnowledgeEvidence(
                chunkId,
                documentId,
                productId,
                source.path("content").asText(""),
                source.path("source_file").asText(documentId),
                1.0 / rank);
    }

    private List<ProductKnowledgeEvidence> mergeAndRerank(
            List<ProductKnowledgeEvidence> esEvidence,
            List<ProductKnowledgeEvidence> vectorEvidence,
            int topK) {
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, ProductKnowledgeEvidence> byChunk = new LinkedHashMap<>();

        addRrf(scores, byChunk, esEvidence, properties.rrfK());
        addRrf(scores, byChunk, vectorEvidence, properties.rrfK());

        List<ProductKnowledgeEvidence> sorted = scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
                .map(entry -> withScore(byChunk.get(entry.getKey()), entry.getValue()))
                .toList();
        return roundRobinByProduct(sorted, topK);
    }

    private void addRrf(
            Map<String, Double> scores,
            Map<String, ProductKnowledgeEvidence> byChunk,
            List<ProductKnowledgeEvidence> evidence,
            int k) {
        int rank = 1;
        for (ProductKnowledgeEvidence item : evidence) {
            if (!StringUtils.hasText(item.chunkId())) {
                continue;
            }
            scores.merge(item.chunkId(), 1.0 / (k + rank), Double::sum);
            byChunk.putIfAbsent(item.chunkId(), item);
            rank++;
        }
    }

    private List<ProductKnowledgeEvidence> roundRobinByProduct(
            List<ProductKnowledgeEvidence> sorted,
            int topK) {
        if (sorted.isEmpty() || topK <= 0) {
            return List.of();
        }

        Map<String, List<ProductKnowledgeEvidence>> byProduct = new LinkedHashMap<>();
        for (ProductKnowledgeEvidence evidence : sorted) {
            byProduct.computeIfAbsent(evidence.productId(), ignored -> new ArrayList<>()).add(evidence);
        }

        List<ProductKnowledgeEvidence> result = new ArrayList<>();
        for (int round = 0; result.size() < topK; round++) {
            boolean added = false;
            for (List<ProductKnowledgeEvidence> productEvidence : byProduct.values()) {
                if (round < productEvidence.size()) {
                    result.add(productEvidence.get(round));
                    added = true;
                    if (result.size() == topK) {
                        break;
                    }
                }
            }
            if (!added) {
                break;
            }
        }
        return List.copyOf(result);
    }

    private ProductKnowledgeEvidence withScore(ProductKnowledgeEvidence evidence, double score) {
        return new ProductKnowledgeEvidence(
                evidence.chunkId(),
                evidence.documentId(),
                evidence.productId(),
                evidence.content(),
                evidence.sourceId(),
                score);
    }

    private List<String> normalizeValues(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                normalized.add(value.trim());
            }
        }
        return List.copyOf(normalized);
    }

    private int recallSize() {
        return Math.max(20, Math.max(1, properties.topK()) * 3);
    }

    private ProductRetrievalIssue issue(
            String stage,
            String code,
            String message,
            List<String> affectedProductIds) {
        return new ProductRetrievalIssue(stage, code, message, affectedProductIds);
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String normalizeBase(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private record MetadataProductMatch(
            ProductMetadata product,
            int recognizedCount,
            int score,
            boolean contradicted) {
    }

    private record MetadataTermMatch(
            boolean recognized,
            boolean matched,
            boolean contradicted) {

        private static MetadataTermMatch ignored() {
            return new MetadataTermMatch(false, false, false);
        }

        private static MetadataTermMatch recognized(boolean matched) {
            return new MetadataTermMatch(true, matched, false);
        }
    }
}
