package com.privatebank.business.dto.customer.graph;

import java.util.List;

public record GraphResponse(
        Long customerId,
        String rootNodeId,
        List<GraphNodeResponse> nodes,
        List<GraphEdgeResponse> edges,
        boolean truncated) {
}
