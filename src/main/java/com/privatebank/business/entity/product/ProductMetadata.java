package com.privatebank.business.entity.product;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@TableName("product_metadata")
public class ProductMetadata {

    @TableId(value = "product_id", type = IdType.INPUT)
    private String productId;

    private String productCode;
    private String registrationCode;
    private String salesCode;
    private String productName;
    private String productCategory;
    private String incomeType;
    private String riskLevel;
    private String operationMode;
    private String termType;
    private Integer termDays;
    private String liquidityRule;
    private BigDecimal minimumInitialAmount;
    private String currency;
    private String targetCustomer;
    private String eligibilityConditions;
    private LocalDate maturityDate;
    private String sourceId;
    private String productStatus;
}
