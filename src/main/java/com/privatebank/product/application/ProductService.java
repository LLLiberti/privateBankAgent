package com.privatebank.product.application;

import com.privatebank.common.api.PageResponse;
import com.privatebank.product.api.ProductResourceResponse;
import com.privatebank.product.domain.ProductServiceResource;
import com.privatebank.product.repository.ProductServiceResourceRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductService {

    private final ProductServiceResourceRepository repository;

    public PageResponse<ProductResourceResponse> list(
            String keyword, String resourceType, String riskLevel, int pageNo, int pageSize) {
        LocalDate today = LocalDate.now();
        Specification<ProductServiceResource> specification = (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(builder.equal(root.get("saleStatus"), "ACTIVE"));
            predicates.add(builder.or(
                    builder.isNull(root.get("effectiveDate")),
                    builder.lessThanOrEqualTo(root.get("effectiveDate"), today)));
            predicates.add(builder.or(
                    builder.isNull(root.get("expiryDate")),
                    builder.greaterThanOrEqualTo(root.get("expiryDate"), today)));
            if (StringUtils.hasText(keyword)) {
                String pattern = "%" + keyword.trim() + "%";
                predicates.add(builder.or(
                        builder.like(root.get("resourceName"), pattern),
                        builder.like(root.get("resourceCode"), pattern)));
            }
            if (StringUtils.hasText(resourceType)) {
                predicates.add(builder.equal(root.get("resourceType"), resourceType));
            }
            if (StringUtils.hasText(riskLevel)) {
                predicates.add(builder.equal(root.get("riskLevel"), riskLevel));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
        var page = repository.findAll(specification,
                PageRequest.of(pageNo - 1, pageSize, Sort.by("resourceName").ascending()));
        return PageResponse.of(page.getContent().stream().map(ProductResourceResponse::from).toList(),
                page.getTotalElements(), pageNo, pageSize);
    }
}
