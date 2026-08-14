package com.privatebank.agent.infrastructure.kyc;

import com.privatebank.agent.domain.kyc.KycCustomerData;
import com.privatebank.agent.application.kyc.KycGraphDataLoader;
import com.privatebank.business.common.exception.BusinessException;
import com.privatebank.business.common.exception.ErrorCode;
import com.privatebank.business.dto.customer.CustomerSummaryResponse;
import com.privatebank.business.mapper.customer.CustomerDataMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class KycCustomerDataLoader {

    private final CustomerDataMapper customerDataMapper;
    private final KycGraphDataLoader graphDataLoader;

    @Autowired
    public KycCustomerDataLoader(CustomerDataMapper customerDataMapper, KycGraphDataLoader graphDataLoader) {
        this.customerDataMapper = customerDataMapper;
        this.graphDataLoader = graphDataLoader;
    }

    public KycCustomerDataLoader(CustomerDataMapper customerDataMapper) {
        this(customerDataMapper, ignored -> List.of());
    }

    public KycCustomerData load(Long personId) {
        CustomerSummaryResponse summary = customerDataMapper.findSummary(personId);
        if (summary == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, "客户不存在");
        }
        return new KycCustomerData(
                summary,
                emptyMap(customerDataMapper.findProfile(personId)),
                emptyList(customerDataMapper.findCareers(personId)),
                emptyList(customerDataMapper.findRiskPreferences(personId)),
                emptyList(customerDataMapper.findFinancialFacts(personId)),
                emptyList(customerDataMapper.findHoldings(personId)),
                emptyList(customerDataMapper.findEvents(personId)),
                emptyList(customerDataMapper.findServiceRecords(personId)),
                emptyList(customerDataMapper.findInteractionNotes(personId)),
                emptyList(customerDataMapper.findEnterpriseRelations(personId)),
                emptyList(customerDataMapper.findEnterpriseBusinesses(personId)),
                emptyList(customerDataMapper.findEnterpriseFinancialMetrics(personId)),
                emptyList(customerDataMapper.findEnterpriseEvents(personId)),
                emptyList(customerDataMapper.findEnterpriseMarketRelations(personId)),
                emptyList(customerDataMapper.findFamilyMembers(personId)),
                emptyList(customerDataMapper.findFamilyRelations(personId)),
                emptyList(customerDataMapper.findSuccessionArrangements(personId)),
                emptyList(customerDataMapper.findSocialRelations(personId)),
                emptyList(customerDataMapper.findSocialActivities(personId)),
                emptyList(customerDataMapper.findPublicReputation(personId)),
                emptyList(customerDataMapper.findReputationRisks(personId)),
                graphDataLoader.loadRelationships(personId));
    }

    private Map<String, Object> emptyMap(Map<String, Object> value) {
        return value == null ? Map.of() : value;
    }

    private List<Map<String, Object>> emptyList(List<Map<String, Object>> value) {
        return value == null ? List.of() : value;
    }
}
