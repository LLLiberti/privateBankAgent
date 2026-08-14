package com.privatebank.agent.infrastructure.kyc;

import com.privatebank.agent.domain.kyc.KycGraphRelationship;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("live")
@EnabledIfSystemProperty(named = "private-bank.test.live-neo4j-kyc", matches = "true")
class KycNeo4jLiveTest {

    private static final String URI = configuredProperty("spring.neo4j.uri", "");
    private static final String USERNAME = configuredProperty("spring.neo4j.authentication.username", "");
    private static final String PASSWORD = configuredProperty("spring.neo4j.authentication.password", "");
    private static final String DATABASE = configuredProperty("spring.neo4j.database", "neo4j");

    @BeforeAll
    static void requireLiveConfiguration() {
        assertThat(URI).as("application.yml must configure spring.neo4j.uri").isNotBlank();
    }

    @Test
    void readsRelationshipProjectionForConfiguredCustomer() {
        Neo4jKycGraphDataLoader loader = new Neo4jKycGraphDataLoader(URI, USERNAME, PASSWORD, DATABASE);
        try {
            List<KycGraphRelationship> relationships = loader.loadRelationships(1L);

            assertThat(relationships).isNotEmpty().hasSizeLessThanOrEqualTo(200);
            assertThat(relationships).allSatisfy(relationship -> {
                assertThat(relationship.relationType()).matches("[A-Z][A-Z0-9_]*");
                assertThat(relationship.startNodeType()).matches("[A-Z][A-Z0-9_]*");
                assertThat(relationship.endNodeType()).matches("[A-Z][A-Z0-9_]*");
                assertThat(relationship.sourceId()).isNotNull();
                assertThat(relationship.distance()).isBetween(1, 2);
            });
            assertThat(relationships).anyMatch(item -> item.startIsCustomer() || item.endIsCustomer());
        } finally {
            loader.close();
        }
    }

    private static String configuredProperty(String name, String fallback) {
        try {
            List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
                    "application.yml", new FileSystemResource("src/main/resources/application.yml"));
            for (PropertySource<?> source : sources) {
                Object value = source.getProperty(name);
                if (value != null) {
                    return resolveEnvironmentPlaceholder(String.valueOf(value));
                }
            }
            return fallback;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read live Neo4j KYC test configuration", exception);
        }
    }

    private static String resolveEnvironmentPlaceholder(String value) {
        if (!value.startsWith("${") || !value.endsWith("}")) {
            return value;
        }
        String expression = value.substring(2, value.length() - 1);
        int separator = expression.indexOf(':');
        String environmentKey = separator < 0 ? expression : expression.substring(0, separator);
        String defaultValue = separator < 0 ? "" : expression.substring(separator + 1);
        String environmentValue = System.getenv(environmentKey);
        return environmentValue == null || environmentValue.isBlank() ? defaultValue : environmentValue;
    }
}
