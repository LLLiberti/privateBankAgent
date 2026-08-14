package com.privatebank.business.dto.customer.graph;

import java.util.Map;

public record GraphNodeResponse(
        String id,
        String businessId,
        GraphNodeType type,
        String label,
        boolean expandable,
        Map<String, Object> properties) {
}
