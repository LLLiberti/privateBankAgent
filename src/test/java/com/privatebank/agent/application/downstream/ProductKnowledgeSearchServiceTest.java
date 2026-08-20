package com.privatebank.agent.application.downstream;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.agent.domain.downstream.ProductKnowledgeSearchResult;
import com.privatebank.business.config.ProductKnowledgeProperties;
import com.privatebank.business.entity.product.ProductMetadata;
import com.privatebank.business.mapper.product.ProductMetadataMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductKnowledgeSearchServiceTest {

    @BeforeAll
    static void initializeProductMetadataTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new MybatisConfiguration(), "ProductKnowledgeSearchServiceTest");
        assistant.setCurrentNamespace(ProductMetadataMapper.class.getName());
        TableInfoHelper.initTableInfo(assistant, ProductMetadata.class);
    }

    @Test
    void rejectsEmptyQueriesBeforeLoadingProducts() {
        ProductMetadataMapper metadataMapper = mock(ProductMetadataMapper.class);
        ProductDocumentIdResolver documentResolver = mock(ProductDocumentIdResolver.class);
        ProductKnowledgeSearchService service = service(metadataMapper, documentResolver, disabledProperties());

        ProductKnowledgeSearchResult result = service.search(List.of(" "), null, "PR2", "ACTIVE");

        assertThat(result.candidateProductIds()).isEmpty();
        assertThat(result.evidence()).isEmpty();
        assertThat(result.retrievalIssues()).extracting("code").containsExactly("EMPTY_QUERY");
        verify(metadataMapper, never()).selectList(any());
        verify(documentResolver, never()).resolve(any());
    }

    @Test
    void loadsHardWhitelistOnceAndFiltersMetadataInMemory() {
        ProductMetadata product = product("P-1", "稳健型");
        product.setProductCategory("固定收益类");
        ProductMetadataMapper metadataMapper = mock(ProductMetadataMapper.class);
        ProductDocumentIdResolver documentResolver = mock(ProductDocumentIdResolver.class);
        ProductKnowledgeSearchService service = service(metadataMapper, documentResolver, disabledProperties());
        when(metadataMapper.selectList(any())).thenReturn(List.of(product));
        when(documentResolver.resolve(List.of("P-1"))).thenReturn(
                new ProductDocumentIdResolver.Resolution(
                        List.of("D-1"), Map.of("D-1", "P-1"), List.of()));

        ProductKnowledgeSearchResult result = service.search(
                List.of("稳健型", "固收类"), null, "PR2", null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<ProductMetadata>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(metadataMapper).selectList(wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSegment())
                .contains("product_status", "risk_level")
                .doesNotContain("eligibility_conditions", "product_category", "income_type");
        verify(documentResolver).resolve(List.of("P-1"));
        assertThat(result.retrievalIssues()).extracting("code")
                .contains("ELASTICSEARCH_NOT_CONFIGURED", "QDRANT_NOT_CONFIGURED", "NO_PRODUCT_EVIDENCE")
                .doesNotContain("NO_METADATA_MATCH");
    }

    @Test
    void explicitProductIdsStillPassThroughHardWhitelistQuery() {
        ProductMetadata product = product("P-1", "稳健型");
        ProductMetadataMapper metadataMapper = mock(ProductMetadataMapper.class);
        ProductDocumentIdResolver documentResolver = mock(ProductDocumentIdResolver.class);
        ProductKnowledgeSearchService service = service(metadataMapper, documentResolver, disabledProperties());
        when(metadataMapper.selectList(any())).thenReturn(List.of(product));
        when(documentResolver.resolve(List.of("P-1"))).thenReturn(
                new ProductDocumentIdResolver.Resolution(List.of(), Map.of(), List.of("P-1")));

        service.search(List.of("稳健型"), List.of("P-1", "P-1"), null, "ACTIVE");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<ProductMetadata>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(metadataMapper).selectList(wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSegment()).contains("product_id");
        verify(documentResolver).resolve(List.of("P-1"));
    }

    @Test
    void resolvesOnlyProductsMatchedByMetadataTerms() {
        ProductMetadata matched = product("P-1", "稳健型、平衡型");
        matched.setProductCategory("固定收益类");
        matched.setRiskLevel("PR2");
        ProductMetadata unmatched = product("P-2", "成长型、进取型");
        unmatched.setProductCategory("权益类");
        unmatched.setRiskLevel("PR4");
        ProductMetadataMapper metadataMapper = mock(ProductMetadataMapper.class);
        ProductDocumentIdResolver documentResolver = mock(ProductDocumentIdResolver.class);
        ProductKnowledgeSearchService service = service(metadataMapper, documentResolver, disabledProperties());
        when(metadataMapper.selectList(any())).thenReturn(List.of(unmatched, matched));
        when(documentResolver.resolve(List.of("P-1"))).thenReturn(
                new ProductDocumentIdResolver.Resolution(List.of(), Map.of(), List.of("P-1")));

        ProductKnowledgeSearchResult result = service.search(
                List.of("稳健型 固收类 理财产品"), null, null, null);

        verify(metadataMapper).selectList(any());
        verify(documentResolver).resolve(List.of("P-1"));
        assertThat(result.retrievalIssues()).extracting("code")
                .contains("MISSING_DOCUMENT_MAPPING", "NO_PRODUCT_DOCUMENT")
                .doesNotContain("NO_METADATA_MATCH");
    }

    @Test
    void rejectsContradictoryPrincipalSafetyMetadataBeforeEvidenceRecall() {
        ProductMetadata product = product("P-1", "稳健型");
        product.setProductCategory("固定收益类");
        product.setIncomeType("非保本浮动收益型");
        ProductMetadataMapper metadataMapper = mock(ProductMetadataMapper.class);
        ProductDocumentIdResolver documentResolver = mock(ProductDocumentIdResolver.class);
        ProductKnowledgeSearchService service = service(metadataMapper, documentResolver, disabledProperties());
        when(metadataMapper.selectList(any())).thenReturn(List.of(product));

        ProductKnowledgeSearchResult result = service.search(
                List.of("稳健型 本金安全 确定性收益"), null, null, null);

        assertThat(result.candidateProductIds()).isEmpty();
        assertThat(result.evidence()).isEmpty();
        assertThat(result.retrievalIssues()).extracting("code").containsExactly("NO_METADATA_MATCH");
        verify(documentResolver, never()).resolve(any());
    }

    @Test
    void recallsEsAndQdrantEvidenceInsideDocumentWhitelist() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> embeddingRequest = new AtomicReference<>();
        AtomicReference<String> qdrantRequest = new AtomicReference<>();
        server.createContext("/chunks/_search", exchange -> respond(exchange, """
                {"hits":{"hits":[{"_source":{"chunk_id":"C-ES","document_id":"D-1",
                "content":"固定收益产品证据","source_file":"source.pdf"}}]}}
                """));
        server.createContext("/embeddings", exchange -> {
            embeddingRequest.set(readBody(exchange));
            respond(exchange, """
                    {"data":[{"embedding":[0.1,0.2]}]}
                    """);
        });
        server.createContext("/collections/chunks/points/search", exchange -> {
            qdrantRequest.set(readBody(exchange));
            respond(exchange, """
                    {"result":[{"payload":{"chunk_id":"C-Q","document_id":"D-1",
                    "content":"稳健型客户适用证据","source_file":"source.pdf"}}]}
                    """);
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            ProductMetadata product = product("P-1", "稳健型");
            product.setProductCategory("固定收益类");
            ProductMetadataMapper metadataMapper = mock(ProductMetadataMapper.class);
            ProductDocumentIdResolver documentResolver = mock(ProductDocumentIdResolver.class);
            ProductKnowledgeProperties properties = new ProductKnowledgeProperties(
                    new ProductKnowledgeProperties.Qdrant("127.0.0.1", port, "qdrant-key", "chunks", false),
                    new ProductKnowledgeProperties.Elasticsearch("chunks"),
                    new ProductKnowledgeProperties.Embedding(
                            "http://127.0.0.1:" + port, "embedding-key", "text-embedding-v4", 2),
                    10,
                    60);
            ProductKnowledgeSearchService service = service(metadataMapper, documentResolver, properties);
            ReflectionTestUtils.setField(service, "esUris", "http://127.0.0.1:" + port);
            when(metadataMapper.selectList(any())).thenReturn(List.of(product));
            when(documentResolver.resolve(List.of("P-1"))).thenReturn(
                    new ProductDocumentIdResolver.Resolution(
                            List.of("D-1"), Map.of("D-1", "P-1"), List.of()));

            ProductKnowledgeSearchResult result = service.search(
                    List.of("稳健型", "固收类"), null, null, null);

            assertThat(result.candidateProductIds()).containsExactly("P-1");
            assertThat(result.evidence()).extracting("chunkId").containsExactly("C-ES", "C-Q");
            assertThat(result.evidence()).allSatisfy(evidence -> {
                assertThat(evidence.productId()).isEqualTo("P-1");
                assertThat(evidence.score()).isPositive();
            });
            assertThat(result.retrievalIssues()).isEmpty();
            assertThat(embeddingRequest.get()).contains("稳健型 固收类", "text-embedding-v4");
            assertThat(qdrantRequest.get()).contains("\"any\":[\"D-1\"]");
        } finally {
            server.stop(0);
        }
    }

    private ProductKnowledgeSearchService service(
            ProductMetadataMapper metadataMapper,
            ProductDocumentIdResolver documentResolver,
            ProductKnowledgeProperties properties) {
        ProductKnowledgeSearchService service = new ProductKnowledgeSearchService(
                metadataMapper, documentResolver, properties, new ObjectMapper().findAndRegisterModules());
        ReflectionTestUtils.setField(service, "esUris", "");
        ReflectionTestUtils.setField(service, "esUsername", "");
        ReflectionTestUtils.setField(service, "esPassword", "");
        return service;
    }

    private ProductKnowledgeProperties disabledProperties() {
        return new ProductKnowledgeProperties(
                new ProductKnowledgeProperties.Qdrant("", 6333, "", "chunks", false),
                new ProductKnowledgeProperties.Elasticsearch("chunks"),
                new ProductKnowledgeProperties.Embedding("", "", "", 1024),
                10,
                60);
    }

    private ProductMetadata product(String productId, String eligibilityConditions) {
        ProductMetadata product = new ProductMetadata();
        product.setProductId(productId);
        product.setEligibilityConditions(eligibilityConditions);
        product.setProductStatus("ACTIVE");
        return product;
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
