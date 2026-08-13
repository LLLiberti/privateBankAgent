package com.privatebank.agent.infrastructure.kyc;

import com.privatebank.agent.application.kyc.KycGraphDataLoader;
import com.privatebank.agent.domain.kyc.KycGraphRelationship;
import com.privatebank.business.dto.customer.CustomerSummaryResponse;
import com.privatebank.business.mapper.customer.CustomerDataMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KycCustomerDataLoaderTest {

    @Test
    void combinesMysqlFactsWithNeo4jRelationshipProjection() {
        CustomerDataMapper mapper = mock(CustomerDataMapper.class);
        KycGraphDataLoader graphDataLoader = mock(KycGraphDataLoader.class);
        KycGraphRelationship relationship = new KycGraphRelationship(
                "PERSON:1", "PERSON", true, "CONTROLS", "ENTERPRISE:10", "ENTERPRISE", false,
                101L, "VERIFIED", 0.98, 1);
        when(mapper.findSummary(1L)).thenReturn(
                new CustomerSummaryResponse(1L, "Test Customer", null, "ENTREPRENEUR", "VERIFIED", "MEDIUM"));
        when(graphDataLoader.loadRelationships(1L)).thenReturn(List.of(relationship));

        var result = new KycCustomerDataLoader(mapper, graphDataLoader).load(1L);

        assertThat(result.graphRelationships()).containsExactly(relationship);
        verify(graphDataLoader).loadRelationships(1L);
    }
}
