package com.privatebank.business.graph;

import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Relationship;

public record GraphRow(
        Node anchor,
        Relationship relationship,
        Node neighbor,
        String sourceId,
        String targetId) {
}
