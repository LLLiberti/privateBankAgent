package com.privatebank.business.graph;

import com.privatebank.business.config.GraphProperties;
import com.privatebank.business.dto.customer.graph.GraphNodeType;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphQueryPolicyTest {
    private final GraphProperties properties = new GraphProperties();
    private final GraphQueryPolicy policy = new GraphQueryPolicy(properties);

    @Test
    void capsClientLimitsAtServerPolicy() {
        properties.setMaxInitialNodes(80);
        properties.setMaxExpandNodes(30);
        assertThat(policy.initialNodeLimit(500)).isEqualTo(80);
        assertThat(policy.expandNodeLimit(500)).isEqualTo(30);
        assertThat(policy.initialNodeLimit(20)).isEqualTo(20);
    }

    @Test
    void rejectsNonPositiveLimits() {
        assertThatThrownBy(() -> policy.initialNodeLimit(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mapsRequestedTypesToFixedAllowLists() {
        assertThat(policy.neo4jLabels(Set.of(
                GraphNodeType.ENTERPRISE,
                GraphNodeType.ENTERPRISE_REFERENCE)))
                .containsExactly("Enterprise");
        assertThat(policy.allowedRelationTypes())
                .contains("CHAIRMAN_OF", "FAMILY_OF", "HAS_EVENT")
                .doesNotContain("ADMIN_RELATION");
    }
}
