package com.privatebank.business.dto.customer;

import java.util.Map;

public record CustomerDetailResponse(
        CustomerSummaryResponse summary,
        Map<String, Object> profile,
        Map<String, Long> dimensionCounts) {
}
