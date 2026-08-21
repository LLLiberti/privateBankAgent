package com.privatebank;

import com.privatebank.agent.config.AgentScopeProperties;
import com.privatebank.business.config.GraphProperties;
import com.privatebank.business.config.JwtProperties;
import com.privatebank.business.config.ProductKnowledgeProperties;
import com.privatebank.business.config.StorageProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.neo4j.Neo4jAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication(exclude = Neo4jAutoConfiguration.class)
@EnableConfigurationProperties({
        AgentScopeProperties.class,
        JwtProperties.class,
        StorageProperties.class,
        ProductKnowledgeProperties.class,
        GraphProperties.class
})
public class PrivateBankApplication {

    public static void main(String[] args) {
        SpringApplication.run(PrivateBankApplication.class, args);
    }
}