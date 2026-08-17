package com.privatebank.agent.infrastructure.kyc;

import com.privatebank.agent.domain.kyc.KycGraphRelationship;
import org.neo4j.driver.Record;
import org.neo4j.driver.Values;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Neo4jKycGraphDataLoaderTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(Neo4jKycGraphDataLoader.class)
            .withPropertyValues("spring.neo4j.uri=");

    @Test
    void createsLoaderThroughSpringWhenMultipleConstructorsExist() {
        contextRunner.run(context -> assertThat(context)
                .hasSingleBean(Neo4jKycGraphDataLoader.class));
    }

    @Test
    void convertsNeo4jBrowserHttpUriToBoltUri() {
        assertThat(Neo4jKycGraphDataLoader.normalizeDriverUri("http://localhost:7474/browser/"))
                .isEqualTo("bolt://localhost:7687");
        assertThat(Neo4jKycGraphDataLoader.normalizeDriverUri("neo4j://graph.internal:7687"))
                .isEqualTo("neo4j://graph.internal:7687");
    }

    @Test
    void returnsEmptyProjectionWhenNeo4jIsNotConfigured() {
        Neo4jKycGraphDataLoader loader = new Neo4jKycGraphDataLoader("", "", "", "neo4j");

        assertThat(loader.loadRelationships(1L)).isEmpty();
    }

    @Test
    void mapsNodeNamesFromRelationshipProjection() {
        Record record = mock(Record.class);
        when(record.get("startNodeId")).thenReturn(Values.value("person:1"));
        when(record.get("startNodeType")).thenReturn(Values.value("Person"));
        when(record.get("startNodeName")).thenReturn(Values.value("  张三  "));
        when(record.get("startIsCustomer")).thenReturn(Values.value(true));
        when(record.get("relationType")).thenReturn(Values.value("CHAIRMAN_OF"));
        when(record.get("endNodeId")).thenReturn(Values.value("enterprise:10"));
        when(record.get("endNodeType")).thenReturn(Values.value("Enterprise"));
        when(record.get("endNodeName")).thenReturn(Values.value("某某科技有限公司"));
        when(record.get("endIsCustomer")).thenReturn(Values.value(false));
        when(record.get("sourceId")).thenReturn(Values.value(101L));
        when(record.get("verificationStatus")).thenReturn(Values.value("VERIFIED"));
        when(record.get("confidence")).thenReturn(Values.value(0.98));
        when(record.get("distance")).thenReturn(Values.value(1));

        Neo4jKycGraphDataLoader loader = new Neo4jKycGraphDataLoader(null, "neo4j");

        KycGraphRelationship relationship = loader.relationship(record);

        assertThat(relationship.startNodeName()).isEqualTo("张三");
        assertThat(relationship.endNodeName()).isEqualTo("某某科技有限公司");
        assertThat(relationship.startNodeType()).isEqualTo("PERSON");
        assertThat(relationship.endNodeType()).isEqualTo("ENTERPRISE");
    }

    @Test
    void rejectsUnsupportedUri() {
        assertThatThrownBy(() -> Neo4jKycGraphDataLoader.normalizeDriverUri("file:///neo4j"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
