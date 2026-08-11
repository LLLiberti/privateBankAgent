package com.privatebank.agent.domain.kyc;

import com.privatebank.business.dto.customer.CustomerSummaryResponse;

import java.util.List;
import java.util.Map;

/** Raw database projection. This object is process-local and is never sent to a model. */
public record KycCustomerData(
        CustomerSummaryResponse summary,
        Map<String, Object> profile,
        List<Map<String, Object>> careers,
        List<Map<String, Object>> riskPreferences,
        List<Map<String, Object>> financialFacts,
        List<Map<String, Object>> holdings,
        List<Map<String, Object>> financialEvents,
        List<Map<String, Object>> serviceRecords,
        List<Map<String, Object>> interactionNotes,
        List<Map<String, Object>> enterpriseRelations,
        List<Map<String, Object>> enterpriseBusinesses,
        List<Map<String, Object>> enterpriseFinancialMetrics,
        List<Map<String, Object>> enterpriseEvents,
        List<Map<String, Object>> enterpriseMarketRelations,
        List<Map<String, Object>> familyMembers,
        List<Map<String, Object>> familyRelations,
        List<Map<String, Object>> successionArrangements,
        List<Map<String, Object>> socialRelations,
        List<Map<String, Object>> socialActivities,
        List<Map<String, Object>> publicReputations,
        List<Map<String, Object>> reputationRisks) {
}
