package com.privatebank.business.dto.customer.graph;

import java.util.Map;

public record GraphEdgeResponse(
        String id,
        String source,
        String target,
        String type,
        String label,
        Map<String, Object> properties) {
}
