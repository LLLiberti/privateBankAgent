package com.privatebank.business.dto.product;

import com.privatebank.business.entity.product.ProductServiceResource;

import java.time.LocalDate;

public record ProductResourceResponse(
        String resourceId,
        String resourceType,
        String resourceCode,
        String resourceName,
        String category,
        String description,
        String riskLevel,
        String saleStatus,
        LocalDate effectiveDate,
        LocalDate expiryDate,
        Integer version,
        Long sourceId) {

    public static ProductResourceResponse from(ProductServiceResource resource) {
        return new ProductResourceResponse(
                resource.getResourceId(), resource.getResourceType(), resource.getResourceCode(),
                resource.getResourceName(), resource.getCategory(), resource.getDescription(),
                resource.getRiskLevel(), resource.getSaleStatus(), resource.getEffectiveDate(),
                resource.getExpiryDate(), resource.getVersion(), resource.getSourceId());
    }
}
