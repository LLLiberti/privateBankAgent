package com.privatebank.agent.infrastructure.kyc;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void rejectsUnsupportedUri() {
        assertThatThrownBy(() -> Neo4jKycGraphDataLoader.normalizeDriverUri("file:///neo4j"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
