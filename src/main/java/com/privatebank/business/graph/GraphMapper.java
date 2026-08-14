package com.privatebank.business.graph;

import com.privatebank.business.dto.customer.graph.GraphEdgeResponse;
import com.privatebank.business.dto.customer.graph.GraphNodeResponse;
import com.privatebank.business.dto.customer.graph.GraphNodeType;
import com.privatebank.business.dto.customer.graph.GraphResponse;
import org.neo4j.driver.Value;
import org.neo4j.driver.types.Entity;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Relationship;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class GraphMapper {
    private static final Map<GraphNodeType, Set<String>> NODE_PROPERTY_ALLOWLIST = Map.of(
            GraphNodeType.PERSON, Set.of("name", "gender", "age", "occupation", "region"),
            GraphNodeType.ENTERPRISE, Set.of("name", "enterprise_name", "industry", "region", "role"),
            GraphNodeType.ENTERPRISE_REFERENCE, Set.of("name", "industry", "region"),
            GraphNodeType.FAMILY_PROFILE, Set.of("name", "family_stage", "member_count"),
            GraphNodeType.FAMILY_MEMBER, Set.of("name", "relationship", "occupation", "age"),
            GraphNodeType.SOCIAL_ORGANIZATION, Set.of("name", "organization_name", "organization_type", "region", "role"),
            GraphNodeType.MARKET_SEGMENT, Set.of("name", "industry", "region"),
            GraphNodeType.EVENT, Set.of("name", "event_type", "event_date", "date_precision", "description"));
    private static final Set<String> EDGE_PROPERTY_ALLOWLIST = Set.of(
            "dimension", "role", "shareholding_ratio", "ownership_ratio", "event_date");
    private static final Map<String, String> EDGE_LABELS = Map.ofEntries(
            Map.entry("CHAIRMAN_OF", "董事长"), Map.entry("CEO_OF", "首席执行官"),
            Map.entry("FOUNDED", "创办"), Map.entry("HOLDS_SHARE", "持股"),
            Map.entry("CONTROLS", "控制"), Map.entry("OWNS", "拥有"),
            Map.entry("FAMILY_OF", "家庭关系"), Map.entry("HAS_FAMILY_PROFILE", "家庭档案"),
            Map.entry("MEMBER_OF", "组织成员"), Map.entry("WORKS_AT", "任职"),
            Map.entry("WORKS_FOR", "任职"), Map.entry("HAS_EVENT", "相关事件"),
            Map.entry("PARTICIPATED_IN", "参与"), Map.entry("RELATED_TO", "关联"));

    public GraphResponse map(Long customerId, String rootNodeId, GraphSlice slice) {
        Map<String, GraphNodeResponse> nodes = new LinkedHashMap<>();
        Map<String, GraphEdgeResponse> edges = new LinkedHashMap<>();
        for (GraphRow row : slice.rows()) {
            addNode(nodes, row.anchor());
            if (row.neighbor() != null) addNode(nodes, row.neighbor());
            if (row.relationship() != null && row.sourceId() != null && row.targetId() != null) {
                GraphEdgeResponse edge = mapEdge(row.relationship(), row.sourceId(), row.targetId());
                edges.putIfAbsent(edge.id(), edge);
            }
        }
        return new GraphResponse(customerId, rootNodeId, List.copyOf(nodes.values()),
                List.copyOf(edges.values()), slice.truncated());
    }

    private void addNode(Map<String, GraphNodeResponse> nodes, Node node) {
        String id = requiredString(node, "entity_id");
        GraphNodeType type = GraphNodeType.fromLabels(node.labels(), id);
        Map<String, Object> properties = allowedProperties(node, NODE_PROPERTY_ALLOWLIST.get(type));
        String label = firstText(node, "name", "enterprise_name", "organization_name", "description");
        if (label == null) label = id;
        String businessId = id.substring(id.indexOf(':') + 1);
        nodes.putIfAbsent(id, new GraphNodeResponse(
                id, businessId, type, label, type != GraphNodeType.EVENT, properties));
    }

    private GraphEdgeResponse mapEdge(Relationship relationship, String sourceId, String targetId) {
        String type = relationship.type();
        String id = optionalString(relationship, "relation_id");
        if (id == null) id = sourceId + "|" + type + "|" + targetId;
        return new GraphEdgeResponse(id, sourceId, targetId, type,
                EDGE_LABELS.getOrDefault(type, type),
                allowedProperties(relationship, EDGE_PROPERTY_ALLOWLIST));
    }

    private Map<String, Object> allowedProperties(Entity entity, Set<String> allowlist) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : allowlist) {
            if (entity.containsKey(key)) {
                Value value = entity.get(key);
                if (!value.isNull()) result.put(key, value.asObject());
            }
        }
        return Map.copyOf(result);
    }

    private String firstText(Entity entity, String... keys) {
        for (String key : keys) {
            String value = optionalString(entity, key);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private String requiredString(Entity entity, String key) {
        String value = optionalString(entity, key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Graph entity is missing " + key);
        return value;
    }

    private String optionalString(Entity entity, String key) {
        if (!entity.containsKey(key) || entity.get(key).isNull()) return null;
        Object value = entity.get(key).asObject();
        return value == null ? null : String.valueOf(value);
    }
}
