package com.privatebank.product.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@TableName("product_service_resource")
public class ProductServiceResource {

    @TableId(value = "resource_id", type = IdType.INPUT)
    private String resourceId;

    private String resourceType;

    private String resourceCode;

    private String resourceName;

    private String category;

    private String description;

    private String riskLevel;

    private String applicableConditions;

    private String regionScope;

    private String saleStatus;

    private LocalDate effectiveDate;

    private LocalDate expiryDate;

    private Long sourceId;

    private String documentId;

    private Integer version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
