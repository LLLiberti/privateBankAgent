package com.privatebank.business.controller.product;

import com.privatebank.business.dto.product.ProductCatalogResponse;
import com.privatebank.business.dto.common.PageResponse;
import com.privatebank.business.service.product.ProductService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @GetMapping
    public PageResponse<ProductCatalogResponse> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String productCategory,
            @RequestParam(required = false) String riskLevel,
            @RequestParam(defaultValue = "1") @Min(1) int pageNo,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return productService.listAvailable(keyword, productCategory, riskLevel, pageNo, pageSize);
    }
}
