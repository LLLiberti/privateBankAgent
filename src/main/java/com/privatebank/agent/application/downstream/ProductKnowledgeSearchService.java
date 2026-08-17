package com.privatebank.agent.application.downstream;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.agent.domain.downstream.ProductKnowledgeEvidence;
import com.privatebank.agent.domain.downstream.ProductKnowledgeSearchResult;
import com.privatebank.business.config.ProductKnowledgeProperties;
import com.privatebank.business.entity.product.ProductMetadata;
import com.privatebank.business.mapper.product.ProductMetadataMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Product knowledge search used by KYP Agent.
 *
 * <p>The service always applies MySQL deterministic filtering first.  It then
 * performs ES BM25 and Qdrant vector recall when the corresponding infrastructure
 * is configured.  Results are merged by chunk_id and ranked with RRF.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductKnowledgeSearchService {

    private final ProductMetadataMapper productMetadataMapper;
    private final ProductKnowledgeProperties properties;
    private final ObjectMapper objectMapper;

    public ProductKnowledgeSearchResult search(
            String query,
            List<String> requestedProductIds,
            String riskLevel,
            String region,
            String saleStatus) {
        List<String> candidates = filterCandidates(query, requestedProductIds, riskLevel, region, saleStatus);
        if (candidates.isEmpty()) {
            return new ProductKnowledgeSearchResult(candidates, List.of());
        }

        List<ProductKnowledgeEvidence> esEvidence = esSearch(query, candidates);
        List<ProductKnowledgeEvidence> vectorEvidence = qdrantSearch(query, candidates);
        List<ProductKnowledgeEvidence> merged = mergeByRrf(esEvidence, vectorEvidence, properties.topK());
        return new ProductKnowledgeSearchResult(candidates, merged);
    }

    private List<String> filterCandidates(
            String query,
            List<String> requestedProductIds,
            String riskLevel,
            String region,
            String saleStatus) {
        if (requestedProductIds != null && !requestedProductIds.isEmpty()) {
            return requestedProductIds.stream().distinct().toList();
        }
        var wrapper = Wrappers.<ProductMetadata>lambdaQuery()
                .eq(StringUtils.hasText(riskLevel), ProductMetadata::getRiskLevel, riskLevel)
                .eq(StringUtils.hasText(saleStatus), ProductMetadata::getProductStatus, saleStatus)
                .and(StringUtils.hasText(query), q -> q
                        .like(ProductMetadata::getProductName, query)
                        .or().like(ProductMetadata::getProductCode, query)
                        .or().like(ProductMetadata::getSalesCode, query))
                .orderByAsc(ProductMetadata::getProductId);
        return productMetadataMapper.selectList(wrapper).stream()
                .map(ProductMetadata::getProductId)
                .distinct()
                .toList();
    }

    private List<ProductKnowledgeEvidence> esSearch(String query, List<String> productIds) {
        ProductKnowledgeProperties.Elasticsearch es = properties.elasticsearch();
        if (!StringUtils.hasText(es.uris()) || !StringUtils.hasText(query)) {
            return List.of();
        }
        try {
            RestClient client = RestClient.builder()
                    .baseUrl(normalizeBase(es.uris()))
                    .defaultHeaders(headers -> {
                        if (StringUtils.hasText(es.username()) && StringUtils.hasText(es.password())) {
                            headers.setBasicAuth(es.username(), es.password());
                        }
                        headers.setContentType(MediaType.APPLICATION_JSON);
                    })
                    .build();
            Map<String, Object> body = Map.of(
                    "size", Math.max(20, properties.topK() * 2),
                    "query", Map.of(
                            "bool", Map.of(
                                    "must", List.of(Map.of("match", Map.of("content", query))),
                                    "filter", List.of(Map.of("terms", Map.of("document_id", productIds))))));
            String response = client.post()
                    .uri("/" + es.index() + "/_search")
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return parseEsResponse(response);
        } catch (Exception exception) {
            log.warn("Elasticsearch product search failed, fallback to empty: {}", exception.getMessage());
            return List.of();
        }
    }

    private List<ProductKnowledgeEvidence> qdrantSearch(String query, List<String> productIds) {
        ProductKnowledgeProperties.Qdrant qdrant = properties.qdrant();
        ProductKnowledgeProperties.Embedding embedding = properties.embedding();
        if (!StringUtils.hasText(qdrant.host()) || !StringUtils.hasText(embedding.apiKey()) || !StringUtils.hasText(query)) {
            return List.of();
        }
        try {
            List<Double> vector = embed(query);
            if (vector == null || vector.isEmpty()) {
                return List.of();
            }
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
                    "limit", Math.max(20, properties.topK() * 2),
                    "with_payload", true,
                    "filter", Map.of("must", List.of(
                            Map.of("key", "document_id", "match", Map.of("value", productIds)))));
            String response = client.post()
                    .uri("/collections/" + qdrant.collectionName() + "/points/search")
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return parseQdrantResponse(response);
        } catch (Exception exception) {
            log.warn("Qdrant product search failed, fallback to empty: {}", exception.getMessage());
            return List.of();
        }
    }

    private List<Double> embed(String text) {
        ProductKnowledgeProperties.Embedding embedding = properties.embedding();
        RestClient client = RestClient.builder()
                .baseUrl(embedding.baseUrl())
                .defaultHeaders(headers -> {
                    if (StringUtils.hasText(embedding.apiKey())) {
                        headers.setBearerAuth(embedding.apiKey());
                    }
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
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode data = root.path("data");
            if (data.isArray() && !data.isEmpty()) {
                List<Double> vector = new ArrayList<>();
                for (JsonNode value : data.get(0).path("embedding")) {
                    vector.add(value.asDouble());
                }
                return vector;
            }
        } catch (Exception exception) {
            log.warn("Embedding response parse failed: {}", exception.getMessage());
        }
        return List.of();
    }

    private List<ProductKnowledgeEvidence> parseEsResponse(String response) {
        try {
            JsonNode hits = objectMapper.readTree(response).path("hits").path("hits");
            List<ProductKnowledgeEvidence> result = new ArrayList<>();
            int rank = 1;
            for (JsonNode hit : hits) {
                JsonNode source = hit.path("_source");
                String chunkId = source.path("chunk_id").asText();
                String documentId = source.path("document_id").asText();
                String content = source.path("content").asText("");
                result.add(new ProductKnowledgeEvidence(
                        chunkId, documentId, documentId, content, documentId, 1.0 / rank));
                rank++;
            }
            return result;
        } catch (Exception exception) {
            log.warn("ES response parse failed: {}", exception.getMessage());
            return List.of();
        }
    }

    private List<ProductKnowledgeEvidence> parseQdrantResponse(String response) {
        try {
            JsonNode result = objectMapper.readTree(response).path("result");
            List<ProductKnowledgeEvidence> list = new ArrayList<>();
            int rank = 1;
            for (JsonNode point : result) {
                JsonNode payload = point.path("payload");
                String chunkId = payload.path("chunk_id").asText();
                String documentId = payload.path("document_id").asText();
                String content = payload.path("content").asText("");
                list.add(new ProductKnowledgeEvidence(
                        chunkId, documentId, documentId, content, documentId, 1.0 / rank));
                rank++;
            }
            return list;
        } catch (Exception exception) {
            log.warn("Qdrant response parse failed: {}", exception.getMessage());
            return List.of();
        }
    }

    private List<ProductKnowledgeEvidence> mergeByRrf(
            List<ProductKnowledgeEvidence> esEvidence,
            List<ProductKnowledgeEvidence> vectorEvidence,
            int topK) {
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, ProductKnowledgeEvidence> byChunk = new LinkedHashMap<>();

        addRrf(scores, byChunk, esEvidence, properties.rrfK());
        addRrf(scores, byChunk, vectorEvidence, properties.rrfK());

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
                .limit(topK)
                .map(entry -> byChunk.get(entry.getKey()))
                .toList();
    }

    private void addRrf(
            Map<String, Double> scores,
            Map<String, ProductKnowledgeEvidence> byChunk,
            List<ProductKnowledgeEvidence> evidence,
            int k) {
        int rank = 1;
        for (ProductKnowledgeEvidence item : evidence) {
            String key = item.chunkId();
            scores.merge(key, 1.0 / (k + rank), Double::sum);
            byChunk.putIfAbsent(key, item);
            rank++;
        }
    }

    private String normalizeBase(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
