package com.privatebank.business.graph;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionConfig;
import org.neo4j.driver.Value;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Relationship;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class Neo4jGraphGateway {
    private static final String NEIGHBORS_QUERY = """
            MATCH (anchor:KGEntity {entity_id: $anchorId})
            OPTIONAL MATCH (anchor)-[r]-(neighbor:KGEntity)
            WHERE neighbor IS NULL OR (
                type(r) IN $allowedRelationTypes
                AND any(label IN labels(neighbor) WHERE label IN $allowedLabels))
            RETURN anchor, r, neighbor,
                   CASE WHEN r IS NULL THEN null ELSE startNode(r).entity_id END AS sourceId,
                   CASE WHEN r IS NULL THEN null ELSE endNode(r).entity_id END AS targetId
            LIMIT $fetchLimit
            """;
    private static final String NODE_EXISTS_QUERY = """
            MATCH (node:KGEntity {entity_id: $nodeId})
            RETURN count(node) > 0 AS found
            """;
    private static final String ROOT_QUERY = """
            MATCH (anchor:KGEntity {entity_id: $anchorId})
            RETURN anchor, null AS r, null AS neighbor, null AS sourceId, null AS targetId
            """;

    private final ObjectProvider<Driver> driverProvider;

    public Neo4jGraphGateway(ObjectProvider<Driver> driverProvider) {
        this.driverProvider = driverProvider;
    }

    public GraphSlice getNeighbors(
            String anchorId, List<String> allowedLabels, List<String> allowedRelationTypes,
            int maxNeighbors, Duration timeout) {
        int fetchLimit = Math.addExact(maxNeighbors, 1);
        if (maxNeighbors == 0) {
            return new GraphSlice(toRows(read(
                    ROOT_QUERY, Map.of("anchorId", anchorId), timeout)), false);
        }

        List<Record> records = read(
                NEIGHBORS_QUERY,
                Map.of(
                        "anchorId", anchorId,
                        "allowedLabels", allowedLabels,
                        "allowedRelationTypes", allowedRelationTypes,
                        "fetchLimit", fetchLimit),
                timeout);
        boolean truncated = records.size() > maxNeighbors;
        List<Record> selected = truncated ? records.subList(0, maxNeighbors) : records;
        return new GraphSlice(toRows(selected), truncated);
    }

    public boolean nodeExists(String nodeId, Duration timeout) {
        List<Record> records = read(NODE_EXISTS_QUERY, Map.of("nodeId", nodeId), timeout);
        return !records.isEmpty() && records.getFirst().get("found").asBoolean(false);
    }

    public boolean isReachable(String rootId, String nodeId, int maxDepth, Duration timeout) {
        String query = switch (maxDepth) {
            case 1 -> reachabilityQuery(1);
            case 2 -> reachabilityQuery(2);
            case 3 -> reachabilityQuery(3);
            default -> throw new IllegalArgumentException("Unsupported graph depth");
        };
        List<Record> records = read(query, Map.of("rootId", rootId, "nodeId", nodeId), timeout);
        return !records.isEmpty() && records.getFirst().get("reachable").asBoolean(false);
    }

    private String reachabilityQuery(int depth) {
        return """
                MATCH (root:KGEntity:Person {entity_id: $rootId})
                MATCH (node:KGEntity {entity_id: $nodeId})
                OPTIONAL MATCH path=(root)-[*1..%d]-(node)
                RETURN path IS NOT NULL AS reachable
                LIMIT 1
                """.formatted(depth);
    }

    private List<Record> read(String cypher, Map<String, Object> parameters, Duration timeout) {
        try {
            Driver driver = driverProvider.getIfAvailable();
            if (driver == null) {
                throw new GraphGatewayException("Neo4j graph driver is unavailable", null);
            }
            try (Session session = driver.session()) {
                return session.run(
                        cypher,
                        parameters,
                        TransactionConfig.builder().withTimeout(timeout).build()).list();
            }
        } catch (GraphGatewayException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new GraphGatewayException("Neo4j graph query failed", exception);
        }
    }

    private List<GraphRow> toRows(List<Record> records) {
        List<GraphRow> rows = new ArrayList<>(records.size());
        for (Record record : records) {
            Node anchor = record.get("anchor").asNode();
            Value relationshipValue = record.get("r");
            Value neighborValue = record.get("neighbor");
            Relationship relationship = relationshipValue.isNull() ? null : relationshipValue.asRelationship();
            Node neighbor = neighborValue.isNull() ? null : neighborValue.asNode();
            rows.add(new GraphRow(
                    anchor, relationship, neighbor,
                    nullableString(record.get("sourceId")),
                    nullableString(record.get("targetId"))));
        }
        return rows;
    }

    private String nullableString(Value value) {
        return value == null || value.isNull() ? null : value.asString();
    }
}

