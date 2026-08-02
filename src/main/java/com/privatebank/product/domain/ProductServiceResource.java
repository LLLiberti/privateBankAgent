package com.privatebank.product.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "product_service_resource")
public class ProductServiceResource {

    @Id
    @Column(name = "resource_id", length = 64, nullable = false)
    private String resourceId;

    @Column(name = "resource_type", length = 16, nullable = false)
    private String resourceType;

    @Column(name = "resource_code", length = 64, unique = true)
    private String resourceCode;

    @Column(name = "resource_name", length = 255, nullable = false)
    private String resourceName;

    @Column(name = "category", length = 64)
    private String category;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "risk_level", length = 16)
    private String riskLevel;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "applicable_conditions", columnDefinition = "json")
    private String applicableConditions;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "region_scope", columnDefinition = "json")
    private String regionScope;

    @Column(name = "sale_status", length = 16, nullable = false)
    private String saleStatus;

    @Column(name = "effective_date")
    private LocalDate effectiveDate;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    @Column(name = "document_id", length = 64)
    private String documentId;

    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
