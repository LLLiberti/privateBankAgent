package com.privatebank.agent.application.downstream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.agent.domain.downstream.ProductKnowledgeSearchResult;
import com.privatebank.business.config.ProductKnowledgeProperties;
import com.privatebank.business.entity.product.ProductMetadata;
import com.privatebank.business.mapper.product.ProductMetadataMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductKnowledgeSearchServiceTest {

    @Test
    void returnsNoCandidatesWithoutCallingRemoteKnowledgeStores() {
        ProductMetadataMapper metadataMapper = mock(ProductMetadataMapper.class);
        ProductDocumentIdResolver documentResolver = mock(ProductDocumentIdResolver.class);
        ProductKnowledgeSearchService service = service(metadataMapper, documentResolver);

        when(metadataMapper.selectList(any())).thenReturn(List.of());

        ProductKnowledgeSearchResult result = service.search("bond", null, "LOW", "ACTIVE");

        assertThat(result.candidateProductIds()).isEmpty();
        assertThat(result.evidence()).isEmpty();
        verify(documentResolver, never()).toDocumentIds(any());
    }

    @Test
    void appliesMySqlCandidateFilteringBeforeKnowledgeRecall() {
        ProductMetadata product = new ProductMetadata();
        product.setProductId("P-1");
        ProductMetadataMapper metadataMapper = mock(ProductMetadataMapper.class);
        ProductDocumentIdResolver documentResolver = mock(ProductDocumentIdResolver.class);
        ProductKnowledgeSearchService service = service(metadataMapper, documentResolver);
        when(metadataMapper.selectList(any())).thenReturn(List.of(product));
        when(documentResolver.toDocumentIds(List.of("P-1"))).thenReturn(List.of());

        ProductKnowledgeSearchResult result = service.search("bond", null, "LOW", "ACTIVE");

        assertThat(result.candidateProductIds()).containsExactly("P-1");
        assertThat(result.evidence()).isEmpty();
        verify(metadataMapper).selectList(any());
        verify(documentResolver).toDocumentIds(List.of("P-1"));
    }

    @Test
    void deduplicatesExplicitProductIdsBeforeRecall() {
        ProductMetadataMapper metadataMapper = mock(ProductMetadataMapper.class);
        ProductDocumentIdResolver documentResolver = mock(ProductDocumentIdResolver.class);
        ProductKnowledgeSearchService service = service(metadataMapper, documentResolver);
        when(documentResolver.toDocumentIds(List.of("P-1", "P-2"))).thenReturn(List.of());

        ProductKnowledgeSearchResult result = service.search(
                "customer need", List.of("P-1", "P-1", "P-2"), null, "ACTIVE");

        assertThat(result.candidateProductIds()).containsExactly("P-1", "P-2");
        verify(metadataMapper, never()).selectList(any());
        verify(documentResolver).toDocumentIds(List.of("P-1", "P-2"));
    }

    private ProductKnowledgeSearchService service(
            ProductMetadataMapper metadataMapper,
            ProductDocumentIdResolver documentResolver) {
        ProductKnowledgeProperties properties = new ProductKnowledgeProperties(
                new ProductKnowledgeProperties.Qdrant("", 6334, "", "chunks", false),
                new ProductKnowledgeProperties.Elasticsearch("chunks"),
                new ProductKnowledgeProperties.Embedding("", "", "", 1024),
                10,
                60);
        return new ProductKnowledgeSearchService(
                metadataMapper, documentResolver, properties, new ObjectMapper().findAndRegisterModules());
    }
}
