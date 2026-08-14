package com.privatebank.business.graph;

import com.privatebank.business.dto.customer.graph.GraphResponse;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Values;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Relationship;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GraphMapperTest {
    private final GraphMapper mapper = new GraphMapper();

    @Test
    void deduplicatesRowsAndReturnsOnlyAllowlistedProperties() {
        Node person = node(List.of("KGEntity", "Person"),
                Map.of("entity_id", "person:1", "name", "张三", "password", "secret"));
        Node enterprise = node(List.of("KGEntity", "Enterprise"),
                Map.of("entity_id", "enterprise:9", "name", "A公司", "industry", "制造业", "embedding", "hidden"));
        Relationship relationship = relationship(Map.of(
                "relation_id", "relation:1", "dimension", "ENTERPRISE", "evidence_text", "sensitive"));
        GraphRow row = new GraphRow(person, relationship, enterprise, "person:1", "enterprise:9");

        GraphResponse response = mapper.map(1L, "person:1", new GraphSlice(List.of(row, row), false));

        assertThat(response.nodes()).hasSize(2);
        assertThat(response.edges()).hasSize(1);
        assertThat(response.nodes().getFirst().properties())
                .containsEntry("name", "张三")
                .doesNotContainKeys("password", "embedding", "evidence_text");
        assertThat(response.edges().getFirst().properties())
                .containsEntry("dimension", "ENTERPRISE")
                .doesNotContainKey("evidence_text");
    }

    private Node node(List<String> labels, Map<String, Object> properties) {
        Node node = mock(Node.class);
        when(node.labels()).thenReturn(labels);
        when(node.containsKey(anyString())).thenAnswer(i -> properties.containsKey(i.getArgument(0)));
        when(node.get(anyString())).thenAnswer(i -> Values.value(properties.get(i.getArgument(0))));
        return node;
    }

    private Relationship relationship(Map<String, Object> properties) {
        Relationship relationship = mock(Relationship.class);
        when(relationship.type()).thenReturn("CHAIRMAN_OF");
        when(relationship.containsKey(anyString())).thenAnswer(i -> properties.containsKey(i.getArgument(0)));
        when(relationship.get(anyString())).thenAnswer(i -> Values.value(properties.get(i.getArgument(0))));
        return relationship;
    }
}
