package com.privatebank.agent.application.downstream;

import com.privatebank.business.mapper.product.ProductDocumentMapper;
import com.privatebank.business.mapper.product.ProductDocumentLink;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves product_metadata.product_id to the document_id used by
 * Elasticsearch/Qdrant product chunks through the product_document mapping table.
 */
@Component
@RequiredArgsConstructor
public class ProductDocumentIdResolver {

    private final ProductDocumentMapper productDocumentMapper;

    public Resolution resolve(List<String> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return Resolution.empty();
        }

        List<ProductDocumentLink> links = productDocumentMapper.findLinksByProductIds(productIds);
        Set<String> documentIds = new LinkedHashSet<>();
        Set<String> mappedProductIds = new LinkedHashSet<>();
        Map<String, String> productIdByDocumentId = new LinkedHashMap<>();
        for (ProductDocumentLink link : links) {
            if (link == null || !StringUtils.hasText(link.productId()) || !StringUtils.hasText(link.documentId())) {
                continue;
            }
            mappedProductIds.add(link.productId());
            documentIds.add(link.documentId());
            productIdByDocumentId.putIfAbsent(link.documentId(), link.productId());
        }

        List<String> unmappedProductIds = productIds.stream()
                .filter(StringUtils::hasText)
                .distinct()
                .filter(productId -> !mappedProductIds.contains(productId))
                .toList();
        return new Resolution(List.copyOf(documentIds), productIdByDocumentId, unmappedProductIds);
    }

    public record Resolution(
            List<String> documentIds,
            Map<String, String> productIdByDocumentId,
            List<String> unmappedProductIds) {

        public Resolution {
            documentIds = documentIds == null ? List.of() : List.copyOf(documentIds);
            productIdByDocumentId = productIdByDocumentId == null
                    ? Map.of()
                    : Map.copyOf(productIdByDocumentId);
            unmappedProductIds = unmappedProductIds == null ? List.of() : List.copyOf(unmappedProductIds);
        }

        public static Resolution empty() {
            return new Resolution(List.of(), Map.of(), List.of());
        }
    }
}
