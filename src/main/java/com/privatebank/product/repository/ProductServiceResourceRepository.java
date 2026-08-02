package com.privatebank.product.repository;

import com.privatebank.product.domain.ProductServiceResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ProductServiceResourceRepository extends
        JpaRepository<ProductServiceResource, String>, JpaSpecificationExecutor<ProductServiceResource> {

    Page<ProductServiceResource> findBySaleStatus(String saleStatus, Pageable pageable);
}
