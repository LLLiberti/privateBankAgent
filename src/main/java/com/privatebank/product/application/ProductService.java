package com.privatebank.product.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.privatebank.common.api.PageResponse;
import com.privatebank.product.api.ProductResourceResponse;
import com.privatebank.product.domain.ProductServiceResource;
import com.privatebank.product.mapper.ProductServiceResourceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductService {

    private final ProductServiceResourceMapper resourceMapper;

    public PageResponse<ProductResourceResponse> list(
            String keyword, String resourceType, String riskLevel, int pageNo, int pageSize) {
        LocalDate today = LocalDate.now();
        var query = Wrappers.<ProductServiceResource>lambdaQuery()
                .eq(ProductServiceResource::getSaleStatus, "ACTIVE")
                .and(wrapper -> wrapper.isNull(ProductServiceResource::getEffectiveDate)
                        .or().le(ProductServiceResource::getEffectiveDate, today))
                .and(wrapper -> wrapper.isNull(ProductServiceResource::getExpiryDate)
                        .or().ge(ProductServiceResource::getExpiryDate, today))
                .and(StringUtils.hasText(keyword), wrapper -> wrapper
                        .like(ProductServiceResource::getResourceName, keyword == null ? null : keyword.trim())
                        .or()
                        .like(ProductServiceResource::getResourceCode, keyword == null ? null : keyword.trim()))
                .eq(StringUtils.hasText(resourceType), ProductServiceResource::getResourceType, resourceType)
                .eq(StringUtils.hasText(riskLevel), ProductServiceResource::getRiskLevel, riskLevel)
                .orderByAsc(ProductServiceResource::getResourceName);
        Page<ProductServiceResource> page = resourceMapper.selectPage(new Page<>(pageNo, pageSize), query);
        return PageResponse.of(page.getRecords().stream().map(ProductResourceResponse::from).toList(),
                page.getTotal(), pageNo, pageSize);
    }
}
