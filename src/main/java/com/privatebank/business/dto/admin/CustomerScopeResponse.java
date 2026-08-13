package com.privatebank.business.dto.admin;

import com.privatebank.business.entity.auth.UserCustomerScope;

import java.time.LocalDateTime;

public record CustomerScopeResponse(
        Long customerId,
        Integer scopeStatus,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static CustomerScopeResponse from(UserCustomerScope scope) {
        return new CustomerScopeResponse(
                scope.getPersonId(), scope.getScopeStatus(), scope.getCreatedAt(), scope.getUpdatedAt());
    }
}
