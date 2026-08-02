package com.privatebank.customer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "customer_personalized_fact")
public class CustomerPersonalizedFact {

    @Id
    @Column(name = "fact_id", length = 64, nullable = false)
    private String factId;

    @Column(name = "person_id", nullable = false)
    private Long personId;

    @Column(name = "dimension", length = 16, nullable = false)
    private String dimension;

    @Column(name = "fact_category", length = 64, nullable = false)
    private String factCategory;

    @Column(name = "fact_key", length = 128, nullable = false)
    private String factKey;

    @Column(name = "fact_value", columnDefinition = "text", nullable = false)
    private String factValue;

    @Column(name = "effective_date")
    private LocalDate effectiveDate;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    @Column(name = "document_id", length = 64, nullable = false)
    private String documentId;

    @Column(name = "verification_status", length = 20, nullable = false)
    private String verificationStatus;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
