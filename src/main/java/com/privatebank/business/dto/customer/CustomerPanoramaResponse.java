package com.privatebank.business.dto.customer;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record CustomerPanoramaResponse(
        Long customerId,
        LocalDateTime asOfTime,
        int completeness,
        Map<String, Object> person,
        Map<String, Object> enterprise,
        Map<String, Object> family,
        Map<String, Object> social,
        List<Map<String, Object>> unresolvedItems) {
}
