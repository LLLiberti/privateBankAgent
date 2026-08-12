package com.privatebank.business.service.product;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.privatebank.business.dto.common.PageResponse;
import com.privatebank.business.dto.product.ProductCatalogResponse;
import com.privatebank.business.entity.product.ProductMetadata;
import com.privatebank.business.mapper.product.ProductMetadataMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductService {

    private final ProductMetadataMapper productMapper;

    public PageResponse<ProductCatalogResponse> listAvailable(
            String keyword, String productCategory, String riskLevel, int pageNo, int pageSize) {
        return list(keyword, productCategory, riskLevel, "ACTIVE", null, pageNo, pageSize);
    }

    public PageResponse<ProductCatalogResponse> listForAdministration(
            String keyword, String productCategory, String riskLevel, String productStatus, String currency,
            int pageNo, int pageSize) {
        return list(keyword, productCategory, riskLevel, productStatus, currency, pageNo, pageSize);
    }

    private PageResponse<ProductCatalogResponse> list(
            String keyword, String productCategory, String riskLevel, String productStatus, String currency,
            int pageNo, int pageSize) {
        String normalizedKeyword = StringUtils.hasText(keyword) ? keyword.trim() : null;
        var query = Wrappers.<ProductMetadata>lambdaQuery()
                .and(normalizedKeyword != null, wrapper -> wrapper
                        .like(ProductMetadata::getProductName, normalizedKeyword)
                        .or().like(ProductMetadata::getProductCode, normalizedKeyword)
                        .or().like(ProductMetadata::getSalesCode, normalizedKeyword)
                        .or().like(ProductMetadata::getRegistrationCode, normalizedKeyword))
                .eq(StringUtils.hasText(productCategory), ProductMetadata::getProductCategory, productCategory)
                .eq(StringUtils.hasText(riskLevel), ProductMetadata::getRiskLevel, riskLevel)
                .eq(StringUtils.hasText(productStatus), ProductMetadata::getProductStatus, productStatus)
                .eq(StringUtils.hasText(currency), ProductMetadata::getCurrency, currency)
                .orderByAsc(ProductMetadata::getProductId);
        Page<ProductMetadata> page = productMapper.selectPage(new Page<>(pageNo, pageSize), query);
        return PageResponse.of(page.getRecords().stream().map(ProductCatalogResponse::from).toList(),
                page.getTotal(), pageNo, pageSize);
    }
}
