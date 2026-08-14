package com.privatebank.business.dto.customer.graph;

import java.util.Arrays;

public enum GraphNodeType {
    PERSON("Person", "person"),
    ENTERPRISE("Enterprise", "enterprise"),
    ENTERPRISE_REFERENCE("Enterprise", "enterprise-reference"),
    FAMILY_PROFILE("FamilyProfile", "family-profile"),
    FAMILY_MEMBER("FamilyMember", "family-member"),
    SOCIAL_ORGANIZATION("Organization", "organization"),
    MARKET_SEGMENT("MarketSegment", "market-segment"),
    EVENT("Event", "event");

    private final String neo4jLabel;
    private final String idPrefix;

    GraphNodeType(String neo4jLabel, String idPrefix) {
        this.neo4jLabel = neo4jLabel;
        this.idPrefix = idPrefix;
    }
    public String neo4jLabel() { return neo4jLabel; }
    public String idPrefix() { return idPrefix; }

    public static GraphNodeType fromNodeId(String nodeId) {
        if (nodeId == null || nodeId.isBlank() || nodeId.length() > 200 || nodeId.contains("/")) {
            throw new IllegalArgumentException("Invalid graph node id");
        }
        int separator = nodeId.indexOf(':');
        if (separator <= 0 || separator == nodeId.length() - 1) {
            throw new IllegalArgumentException("Invalid graph node id");
        }
        String prefix = nodeId.substring(0, separator);
        return Arrays.stream(values())
                .filter(type -> type.idPrefix.equals(prefix))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported graph node type"));
    }

    public static GraphNodeType fromLabels(Iterable<String> labels, String nodeId) {
        GraphNodeType idType = fromNodeId(nodeId);
        for (String label : labels) {
            if (idType.neo4jLabel.equals(label)) return idType;
        }
        throw new IllegalArgumentException("Graph node label does not match its id");
    }
}
