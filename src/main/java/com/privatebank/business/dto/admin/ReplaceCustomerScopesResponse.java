package com.privatebank.business.dto.admin;

import java.util.List;

public record ReplaceCustomerScopesResponse(
        String userId,
        long assignedCustomerCount,
        List<Long> customerIds) {
}
