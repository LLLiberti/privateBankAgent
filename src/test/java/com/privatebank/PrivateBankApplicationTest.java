package com.privatebank;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.neo4j.Neo4jAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

class PrivateBankApplicationTest {

    @Test
    void excludesBootManagedNeo4jDriverBecauseKycLoaderOwnsItsDriver() {
        SpringBootApplication configuration = PrivateBankApplication.class
                .getAnnotation(SpringBootApplication.class);

        assertThat(configuration.exclude()).contains(Neo4jAutoConfiguration.class);
    }
}
