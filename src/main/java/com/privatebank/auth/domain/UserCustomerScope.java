package com.privatebank.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "user_customer_scope", uniqueConstraints =
        @UniqueConstraint(name = "uk_user_customer_scope", columnNames = {"user_id", "person_id"}))
public class UserCustomerScope {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "scope_id")
    private Long scopeId;

    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    @Column(name = "person_id", nullable = false)
    private Long personId;

    @Column(name = "scope_status", nullable = false)
    private Integer scopeStatus;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
