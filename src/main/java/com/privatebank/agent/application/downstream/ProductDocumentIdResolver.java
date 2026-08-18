package com.privatebank.agent.application.downstream;

import com.privatebank.business.mapper.product.ProductDocumentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves product_metadata.product_id to the document_id used by
 * Elasticsearch/Qdrant product chunks through the product_document mapping table.
 */
@Component
@RequiredArgsConstructor
public class ProductDocumentIdResolver {

    private final ProductDocumentMapper productDocumentMapper;

    public List<String> toDocumentIds(List<String> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return List.of();
        }
        List<String> documentIds = productDocumentMapper.findDocumentIdsByProductIds(productIds);
        Set<String> unique = new LinkedHashSet<>(documentIds);
        unique.removeIf(value -> !StringUtils.hasText(value));
        return List.copyOf(unique);
    }

    public String toProductId(String documentId) {
        if (!StringUtils.hasText(documentId)) {
            return documentId;
        }
        String productId = productDocumentMapper.findProductIdByDocumentId(documentId);
        return StringUtils.hasText(productId) ? productId : documentId;
    }
}
