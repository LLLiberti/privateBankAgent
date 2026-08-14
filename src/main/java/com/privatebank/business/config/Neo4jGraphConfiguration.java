package com.privatebank.business.config;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration
public class Neo4jGraphConfiguration {
    @Bean(destroyMethod = "close")
    @Lazy
    @ConditionalOnProperty(name = "private-bank.graph.enabled", havingValue = "true", matchIfMissing = true)
    Driver graphDriver(
            @Value("${spring.neo4j.uri:}") String uri,
            @Value("${spring.neo4j.authentication.username:}") String username,
            @Value("${spring.neo4j.authentication.password:}") String password) {
        if (uri == null || uri.isBlank() || username == null || username.isBlank()) {
            throw new IllegalStateException("Neo4j connection is not configured");
        }
        return GraphDatabase.driver(uri, AuthTokens.basic(username, password == null ? "" : password));
    }
}
