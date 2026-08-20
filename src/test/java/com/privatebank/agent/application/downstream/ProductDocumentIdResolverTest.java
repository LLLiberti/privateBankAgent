package com.privatebank.agent.application.downstream;

import com.privatebank.business.mapper.product.ProductDocumentLink;
import com.privatebank.business.mapper.product.ProductDocumentMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductDocumentIdResolverTest {

    @Test
    void resolvesBatchMappingsAndReportsUnmappedProducts() {
        ProductDocumentMapper mapper = mock(ProductDocumentMapper.class);
        ProductDocumentIdResolver resolver = new ProductDocumentIdResolver(mapper);
        when(mapper.findLinksByProductIds(List.of("P-1", "P-2"))).thenReturn(List.of(
                new ProductDocumentLink("P-1", "D-1"),
                new ProductDocumentLink("P-1", "D-2")));

        ProductDocumentIdResolver.Resolution resolution = resolver.resolve(List.of("P-1", "P-2"));

        assertThat(resolution.documentIds()).containsExactly("D-1", "D-2");
        assertThat(resolution.productIdByDocumentId())
                .containsEntry("D-1", "P-1")
                .containsEntry("D-2", "P-1");
        assertThat(resolution.unmappedProductIds()).containsExactly("P-2");
    }
}
