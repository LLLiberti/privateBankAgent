package com.privatebank.agent.infrastructure.kyc;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.agent.application.kyc.KycDataMaskingService;
import com.privatebank.agent.application.kyc.KycGraphDataLoader;
import com.privatebank.agent.domain.kyc.KycCustomerData;
import com.privatebank.agent.domain.kyc.KycInputValidationException;
import com.privatebank.agent.domain.kyc.KycMaskedInput;
import com.privatebank.business.config.MybatisPlusConfig;
import com.privatebank.business.dto.customer.CustomerSummaryResponse;
import com.privatebank.business.mapper.customer.CustomerDataMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.boot.env.YamlPropertySourceLoader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Read-only coverage test for the fixed demo customers. It stops at the model
 * boundary: real customer data is loaded and masked, but no workflow, artifact,
 * AgentScope component, or model client is created.
 */
@Tag("live")
@EnabledIfSystemProperty(named = "private-bank.test.live-db-kyc-masking", matches = "true")
@SpringJUnitConfig
@ContextConfiguration(classes = KycMaskingCoverageLiveTest.MaskingCoverageConfiguration.class)
class KycMaskingCoverageLiveTest {

    private static final int EXPECTED_CUSTOMER_COUNT = 30;
    private static final String DATASOURCE_URL = configuredProperty("spring.datasource.url", "");
    private static final String DATASOURCE_USERNAME = configuredProperty("spring.datasource.username", "");
    private static final String DATASOURCE_PASSWORD = configuredProperty("spring.datasource.password", "");
    private static final boolean GRAPH_ENABLED = Boolean.parseBoolean(
            configuredProperty("private-bank.graph.enabled", "true"));
    private static final String NEO4J_URI = configuredProperty("spring.neo4j.uri", "");
    private static final String NEO4J_USERNAME = configuredProperty("spring.neo4j.authentication.username", "");
    private static final String NEO4J_PASSWORD = configuredProperty("spring.neo4j.authentication.password", "");
    private static final String NEO4J_DATABASE = configuredProperty("spring.neo4j.database", "neo4j");

    @DynamicPropertySource
    static void liveProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DATASOURCE_URL);
        registry.add("spring.datasource.username", () -> DATASOURCE_USERNAME);
        registry.add("spring.datasource.password", () -> DATASOURCE_PASSWORD);
        registry.add("spring.datasource.hikari.max-lifetime", () -> 240000L);
        registry.add("spring.datasource.hikari.keepalive-time", () -> 60000L);
        registry.add("spring.datasource.hikari.validation-timeout", () -> 5000L);
        registry.add("spring.flyway.enabled", () -> false);
        registry.add("mybatis-plus.configuration.map-underscore-to-camel-case", () -> true);
        registry.add("mybatis-plus.configuration.default-enum-type-handler",
                () -> "org.apache.ibatis.type.EnumTypeHandler");
    }

    @BeforeAll
    static void requireLiveConfiguration() {
        Assertions.assertFalse(DATASOURCE_URL.isBlank(), "application.yml must configure the database URL");
        if (GRAPH_ENABLED) {
            Assertions.assertFalse(NEO4J_URI.isBlank(),
                    "application.yml must configure the Neo4j URI when graph loading is enabled");
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    private CustomerDataMapper customerDataMapper;

    @org.springframework.beans.factory.annotation.Autowired
    private KycCustomerDataLoader customerDataLoader;

    @org.springframework.beans.factory.annotation.Autowired
    private KycDataMaskingService dataMaskingService;

    @Test
    void allDemoCustomersPassTheProductionMaskingBoundary() {
        long totalCustomers = customerDataMapper.countCustomers(null, null);
        assertThat(totalCustomers)
                .as("the masking baseline must cover exactly the fixed demo customer set")
                .isEqualTo(EXPECTED_CUSTOMER_COUNT);

        List<CustomerSummaryResponse> customers = customerDataMapper.findCustomers(
                null, null, 0, EXPECTED_CUSTOMER_COUNT);
        assertThat(customers).hasSize(EXPECTED_CUSTOMER_COUNT);

        List<MaskingFailure> failures = new ArrayList<>();
        int passed = 0;
        int graphRelationshipCount = 0;
        for (CustomerSummaryResponse customer : customers) {
            try {
                KycCustomerData data = customerDataLoader.load(customer.customerId());
                graphRelationshipCount += data.graphRelationships().size();
                KycMaskedInput input = dataMaskingService.mask(data);
                passed++;
                System.out.printf(
                        "[KYC masking PASS] personId=%d customerName=%s graphRelationships=%d "
                                + "evidenceReferences=%d payloadSha256=%s%n",
                        customer.customerId(), printable(customer.fullName()), data.graphRelationships().size(),
                        input.evidenceReferences().size(), input.sha256());
            } catch (KycInputValidationException exception) {
                MaskingFailure failure = MaskingFailure.validation(customer, exception);
                failures.add(failure);
                System.out.println(failure.asConsoleLine());
            } catch (RuntimeException exception) {
                MaskingFailure failure = MaskingFailure.unexpected(customer, rootCause(exception));
                failures.add(failure);
                System.out.println(failure.asConsoleLine());
            }
        }

        System.out.printf(
                "[KYC masking coverage] customerCount=%d passed=%d failed=%d graphEnabled=%s "
                        + "graphRelationships=%d modelCalls=0 databaseWrites=0%n",
                customers.size(), passed, failures.size(), GRAPH_ENABLED, graphRelationshipCount);

        String failureSummary = failures.stream()
                .map(MaskingFailure::asConsoleLine)
                .collect(Collectors.joining(System.lineSeparator()));
        assertThat(failures)
                .withFailMessage("KYC masking coverage failed for %d customer(s):%n%s",
                        failures.size(), failureSummary)
                .isEmpty();
    }

    private static Throwable rootCause(Throwable exception) {
        Throwable current = exception;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static String printable(Object value) {
        if (value == null) {
            return "<null>";
        }
        String oneLine = String.valueOf(value).replace("\r", "\\r").replace("\n", "\\n");
        int codePoints = oneLine.codePointCount(0, oneLine.length());
        if (codePoints <= 1200) {
            return oneLine;
        }
        return oneLine.substring(0, oneLine.offsetByCodePoints(0, 1200)) + "...[truncated]";
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
            throw new IllegalStateException("无法读取真实数据库 KYC 脱敏覆盖测试配置", exception);
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

    private record MaskingFailure(
            Long personId,
            String customerName,
            String reasonCode,
            String fieldPath,
            String rejectedValue,
            String matchedTerm,
            String category,
            String message) {

        private static MaskingFailure validation(
                CustomerSummaryResponse customer, KycInputValidationException exception) {
            return new MaskingFailure(
                    customer.customerId(), customer.fullName(), exception.reasonCode(), exception.fieldPath(),
                    exception.rejectedValue(), exception.matchedTerm(), exception.category(), exception.getMessage());
        }

        private static MaskingFailure unexpected(CustomerSummaryResponse customer, Throwable exception) {
            return new MaskingFailure(
                    customer.customerId(), customer.fullName(), "UNEXPECTED_EXCEPTION", null, null, null,
                    exception.getClass().getName(), exception.getMessage());
        }

        private String asConsoleLine() {
            return "[KYC masking FAIL] personId=" + personId
                    + " customerName=" + printable(customerName)
                    + " reasonCode=" + printable(reasonCode)
                    + " fieldPath=" + printable(fieldPath)
                    + " category=" + printable(category)
                    + " matchedTerm=" + printable(matchedTerm)
                    + " rejectedValue=" + printable(rejectedValue)
                    + " message=" + printable(message);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MybatisPlusConfig.class)
    @ImportAutoConfiguration({
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            TransactionAutoConfiguration.class,
            MybatisPlusAutoConfiguration.class
    })
    static class MaskingCoverageConfiguration {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        KycDataMaskingService kycDataMaskingService(ObjectMapper objectMapper) {
            return new KycDataMaskingService(objectMapper);
        }

        @Bean
        KycGraphDataLoader kycGraphDataLoader() {
            if (!GRAPH_ENABLED) {
                return ignored -> List.of();
            }
            return new Neo4jKycGraphDataLoader(
                    NEO4J_URI, NEO4J_USERNAME, NEO4J_PASSWORD, NEO4J_DATABASE);
        }

        @Bean
        KycCustomerDataLoader kycCustomerDataLoader(
                CustomerDataMapper customerDataMapper, KycGraphDataLoader graphDataLoader) {
            return new KycCustomerDataLoader(customerDataMapper, graphDataLoader);
        }
    }
}
