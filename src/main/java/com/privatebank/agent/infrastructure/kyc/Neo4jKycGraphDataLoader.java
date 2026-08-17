package com.privatebank.agent.infrastructure.kyc;

import com.privatebank.agent.application.kyc.KycGraphDataLoader;
import com.privatebank.agent.domain.kyc.KycGraphRelationship;
import jakarta.annotation.PreDestroy;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.List;
import java.util.Map;

@Component
public class Neo4jKycGraphDataLoader implements KycGraphDataLoader {

    private static final int MAX_RELATIONSHIPS = 200;
    private static final String QUERY = """
            MATCH (customer:Person)
            WHERE toString(customer.person_id) = toString($personId)
            MATCH path = (customer)-[*1..2]-(related)
            WHERE all(edge IN relationships(path)
                WHERE coalesce(edge.graph_status, 'ACTIVE') <> 'INACTIVE')
            UNWIND relationships(path) AS edge
            WITH customer, edge, startNode(edge) AS source, endNode(edge) AS target,
                 min(length(path)) AS distance
            RETURN DISTINCT
                 coalesce(toString(source.entity_id), toString(source.node_id), elementId(source)) AS startNodeId,
                 coalesce(source.node_type, head([label IN labels(source) WHERE label <> 'KGEntity'])) AS startNodeType,
                 coalesce(source.name, source.enterprise_name, source.organization_name) AS startNodeName,
                 source = customer AS startIsCustomer,
                 type(edge) AS relationType,
                 coalesce(toString(target.entity_id), toString(target.node_id), elementId(target)) AS endNodeId,
                 coalesce(target.node_type, head([label IN labels(target) WHERE label <> 'KGEntity'])) AS endNodeType,
                 coalesce(target.name, target.enterprise_name, target.organization_name) AS endNodeName,
                 target = customer AS endIsCustomer,
                 edge.source_id AS sourceId,
                 edge.verification_status AS verificationStatus,
                 edge.confidence AS confidence,
                 distance
            ORDER BY distance, relationType, startNodeId, endNodeId
            LIMIT $limit
            """;

    private final Driver driver;
    private final String database;
    private final boolean configured;

    @Autowired
    public Neo4jKycGraphDataLoader(
            @Value("${spring.neo4j.uri:}") String uri,
            @Value("${spring.neo4j.authentication.username:}") String username,
            @Value("${spring.neo4j.authentication.password:}") String password,
            @Value("${spring.neo4j.database:neo4j}") String database) {
        this.database = normalizeDatabase(database);
        this.configured = StringUtils.hasText(uri);
        this.driver = configured
                ? GraphDatabase.driver(normalizeDriverUri(uri), AuthTokens.basic(username, password))
                : null;
    }

    Neo4jKycGraphDataLoader(Driver driver, String database) {
        this.driver = driver;
        this.database = normalizeDatabase(database);
        this.configured = driver != null;
    }

    @Override
    public List<KycGraphRelationship> loadRelationships(Long personId) {
        if (!configured) {
            return List.of();
        }
        try (Session session = driver.session(SessionConfig.forDatabase(database))) {
            return session.executeRead(transaction -> transaction.run(
                            QUERY, Map.of("personId", personId, "limit", MAX_RELATIONSHIPS))
                    .list(this::relationship));
        }
    }

    KycGraphRelationship relationship(Record record) {
        return new KycGraphRelationship(
                record.get("startNodeId").asString(),
                normalizedCode(record.get("startNodeType")),
                stringValue(record.get("startNodeName")),
                record.get("startIsCustomer").asBoolean(false),
                normalizedCode(record.get("relationType")),
                record.get("endNodeId").asString(),
                normalizedCode(record.get("endNodeType")),
                stringValue(record.get("endNodeName")),
                record.get("endIsCustomer").asBoolean(false),
                longValue(record.get("sourceId")),
                normalizedCode(record.get("verificationStatus")),
                doubleValue(record.get("confidence")),
                record.get("distance").asInt());
    }

    private String stringValue(org.neo4j.driver.Value value) {
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asString().trim();
        return text.isEmpty() ? null : text;
    }

    private String normalizedCode(org.neo4j.driver.Value value) {
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asString().trim().toUpperCase().replaceAll("[^A-Z0-9]+", "_");
    }

    private Long longValue(org.neo4j.driver.Value value) {
        if (value == null || value.isNull()) {
            return null;
        }
        Object raw = value.asObject();
        if (raw instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(value.asString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Double doubleValue(org.neo4j.driver.Value value) {
        if (value == null || value.isNull()) {
            return null;
        }
        Object raw = value.asObject();
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(value.asString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static String normalizeDriverUri(String configuredUri) {
        String trimmed = configuredUri.trim();
        if (trimmed.startsWith("bolt://") || trimmed.startsWith("neo4j://")
                || trimmed.startsWith("bolt+s://") || trimmed.startsWith("neo4j+s://")) {
            return trimmed;
        }
        URI uri = URI.create(trimmed);
        if (("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null) {
            int port = uri.getPort() == 7474 || uri.getPort() < 0 ? 7687 : uri.getPort();
            return "bolt://" + uri.getHost() + ":" + port;
        }
        throw new IllegalArgumentException("Unsupported Neo4j URI: " + configuredUri);
    }

    private static String normalizeDatabase(String database) {
        return StringUtils.hasText(database) ? database.trim() : "neo4j";
    }

    @PreDestroy
    void close() {
        if (driver != null) {
            driver.close();
        }
    }
}
