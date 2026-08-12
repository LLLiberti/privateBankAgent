package com.privatebank.business.dto.product;

import com.privatebank.business.entity.product.ProductMetadata;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ProductCatalogResponse(
        String productId,
        String productCode,
        String registrationCode,
        String salesCode,
        String productName,
        String productCategory,
        String incomeType,
        String riskLevel,
        String operationMode,
        String termType,
        Integer termDays,
        BigDecimal minimumInitialAmount,
        String currency,
        String targetCustomer,
        LocalDate maturityDate,
        String productStatus) {

    public static ProductCatalogResponse from(ProductMetadata product) {
        return new ProductCatalogResponse(
                product.getProductId(), product.getProductCode(), product.getRegistrationCode(),
                product.getSalesCode(), product.getProductName(), product.getProductCategory(),
                product.getIncomeType(), product.getRiskLevel(), product.getOperationMode(),
                product.getTermType(), product.getTermDays(), product.getMinimumInitialAmount(),
                product.getCurrency(), product.getTargetCustomer(), product.getMaturityDate(),
                product.getProductStatus());
    }
}
