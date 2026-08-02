package com.privatebank.business.entity.customer;

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
@TableName("customer_personalized_fact")
public class CustomerPersonalizedFact {

    @TableId(value = "fact_id", type = IdType.INPUT)
    private String factId;

    private Long personId;

    private String dimension;

    private String factCategory;

    private String factKey;

    private String factValue;

    private LocalDate effectiveDate;

    private Long sourceId;

    private String documentId;

    private String verificationStatus;

    private LocalDateTime createdAt;
}
