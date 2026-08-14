package com.privatebank.business.graph;

import com.privatebank.business.config.GraphProperties;
import com.privatebank.business.dto.customer.graph.GraphNodeType;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@Component
public class GraphQueryPolicy {
    private static final List<String> ALLOWED_RELATION_TYPES = List.of(
            "WORKS_AT", "CHAIRMAN_OF", "CEO_OF", "FOUNDED", "HOLDS_SHARE",
            "CONTROLS", "OWNS", "GUARANTEES", "SUPPLY_CHAIN", "COMPETES_WITH",
            "HAS_UPSTREAM", "HAS_DOWNSTREAM", "FAMILY_OF", "HAS_FAMILY_PROFILE",
            "MEMBER_OF", "WORKS_FOR", "CHARITY_COOPERATION", "ACADEMIC_COOPERATION",
            "PARTICIPATED_IN", "HAS_EVENT", "RELATED_TO");

    private final GraphProperties properties;

    public GraphQueryPolicy(GraphProperties properties) {
        this.properties = properties;
    }

    public int initialNodeLimit(Integer requested) {
        int value = requested == null ? properties.getMaxInitialNodes() : requested;
        if (value <= 0) throw new IllegalArgumentException("maxNodes must be positive");
        return Math.min(value, properties.getMaxInitialNodes());
    }

    public int expandNodeLimit(Integer requested) {
        int value = requested == null ? properties.getMaxExpandNodes() : requested;
        if (value <= 0) throw new IllegalArgumentException("maxNodes must be positive");
        return Math.min(value, properties.getMaxExpandNodes());
    }

    public Set<GraphNodeType> allowedTypes(Set<GraphNodeType> requested) {
        if (requested == null || requested.isEmpty()) return EnumSet.allOf(GraphNodeType.class);
        return EnumSet.copyOf(requested);
    }

    public List<String> neo4jLabels(Set<GraphNodeType> requested) {
        return allowedTypes(requested).stream().map(GraphNodeType::neo4jLabel).distinct().toList();
    }

    public int maxDepth() { return properties.getMaxDepth(); }
    public Duration queryTimeout() { return properties.getQueryTimeout(); }
    public boolean enabled() { return properties.isEnabled(); }
    public List<String> allowedRelationTypes() {
        return ALLOWED_RELATION_TYPES;
    }

}
