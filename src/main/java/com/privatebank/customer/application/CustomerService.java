package com.privatebank.customer.application;

import com.privatebank.auth.repository.UserCustomerScopeRepository;
import com.privatebank.common.api.PageResponse;
import com.privatebank.common.exception.BusinessException;
import com.privatebank.common.exception.ErrorCode;
import com.privatebank.customer.api.CustomerDetailResponse;
import com.privatebank.customer.api.CustomerPanoramaResponse;
import com.privatebank.customer.api.CustomerSummaryResponse;
import com.privatebank.customer.api.EvidenceResponse;
import com.privatebank.customer.repository.CustomerDataRepository;
import com.privatebank.security.CurrentUserPrincipal;
import com.privatebank.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CustomerService {

    private final CustomerDataRepository dataRepository;
    private final UserCustomerScopeRepository scopeRepository;
    private final CurrentUserService currentUserService;
    private final RedactionService redactionService;

    public PageResponse<CustomerSummaryResponse> list(
            CurrentUserPrincipal principal, String keyword, int pageNo, int pageSize) {
        List<Long> allowedIds = principal.isSystemAdmin()
                ? null
                : scopeRepository.findActivePersonIds(principal.userId());
        long total = dataRepository.countCustomers(keyword, allowedIds);
        List<CustomerSummaryResponse> items = dataRepository.findCustomers(
                keyword, allowedIds, (pageNo - 1) * pageSize, pageSize);
        return PageResponse.of(items, total, pageNo, pageSize);
    }

    public CustomerDetailResponse detail(CurrentUserPrincipal principal, Long personId) {
        currentUserService.requireCustomerAccess(principal, personId);
        CustomerSummaryResponse summary = summary(personId);
        Map<String, Object> profile = dataRepository.findOne(
                "SELECT * FROM person_profile WHERE person_id = :personId", personId);
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("person", dataRepository.count("financial_fact", personId)
                + dataRepository.count("person_career", personId));
        counts.put("enterprise", dataRepository.count("person_enterprise_relation", personId));
        counts.put("family", dataRepository.count("family_member", personId));
        counts.put("social", dataRepository.count("person_social_relation", personId)
                + dataRepository.count("social_activity", personId));
        return new CustomerDetailResponse(summary, profile, counts);
    }

    public CustomerPanoramaResponse panorama(
            CurrentUserPrincipal principal, Long personId, LocalDateTime asOfTime) {
        currentUserService.requireCustomerAccess(principal, personId);
        CustomerSummaryResponse summary = summary(personId);

        Map<String, Object> person = new LinkedHashMap<>();
        person.put("summary", summary);
        person.put("profile", dataRepository.findOne(
                "SELECT * FROM person_profile WHERE person_id = :personId", personId));
        person.put("careers", dataRepository.findMany(
                "SELECT * FROM person_career WHERE person_id = :personId ORDER BY start_date DESC", personId));
        person.put("riskPreferences", dataRepository.findMany(
                "SELECT * FROM risk_preference WHERE person_id = :personId ORDER BY created_at DESC", personId));
        person.put("financialFacts", dataRepository.findMany(
                "SELECT * FROM financial_fact WHERE person_id = :personId ORDER BY effective_date DESC", personId));
        person.put("holdings", dataRepository.findMany(
                "SELECT * FROM product_holding WHERE person_id = :personId ORDER BY created_at DESC", personId));
        person.put("events", dataRepository.findMany(
                "SELECT * FROM financial_event WHERE person_id = :personId ORDER BY event_date DESC", personId));
        person.put("serviceRecords", dataRepository.findMany(
                "SELECT * FROM service_record WHERE person_id = :personId ORDER BY created_at DESC", personId));
        person.put("interactionNotes", dataRepository.findMany(
                "SELECT * FROM customer_interaction_note WHERE person_id = :personId ORDER BY created_at DESC", personId));

        Map<String, Object> enterprise = Map.of("relations", dataRepository.findMany("""
                SELECT r.*, e.enterprise_name, e.stock_code, e.industry_name, e.headquarters
                  FROM person_enterprise_relation r
                  JOIN enterprise e ON e.enterprise_id = r.enterprise_id
                 WHERE r.person_id = :personId ORDER BY r.is_core_relation DESC, r.created_at DESC
                """, personId));

        Map<String, Object> family = new LinkedHashMap<>();
        family.put("members", dataRepository.findMany(
                "SELECT * FROM family_member WHERE person_id = :personId ORDER BY created_at DESC", personId));
        family.put("successionArrangements", dataRepository.findMany(
                "SELECT * FROM succession_arrangement WHERE person_id = :personId ORDER BY created_at DESC", personId));

        Map<String, Object> social = new LinkedHashMap<>();
        social.put("relations", dataRepository.findMany("""
                SELECT r.*, o.organization_name, o.organization_type
                  FROM person_social_relation r
                  JOIN social_organization o ON o.social_organization_id = r.social_organization_id
                 WHERE r.person_id = :personId ORDER BY r.created_at DESC
                """, personId));
        social.put("activities", dataRepository.findMany(
                "SELECT * FROM social_activity WHERE person_id = :personId ORDER BY activity_date DESC", personId));
        social.put("publicReputation", dataRepository.findMany(
                "SELECT * FROM public_reputation WHERE person_id = :personId ORDER BY publication_date DESC", personId));
        social.put("reputationRisks", dataRepository.findMany(
                "SELECT * FROM reputation_risk WHERE person_id = :personId ORDER BY event_date DESC", personId));

        int populated = (person.size() > 1 ? 1 : 0)
                + (isPopulated(enterprise) ? 1 : 0)
                + (isPopulated(family) ? 1 : 0)
                + (isPopulated(social) ? 1 : 0);
        return new CustomerPanoramaResponse(
                personId,
                asOfTime == null ? LocalDateTime.now() : asOfTime,
                populated * 25,
                person,
                enterprise,
                family,
                social,
                dataRepository.findUnresolved(personId));
    }

    public EvidenceResponse evidence(CurrentUserPrincipal principal, Long sourceId) {
        EvidenceResponse evidence = dataRepository.findEvidence(sourceId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, "来源证据不存在"));
        if (!principal.isSystemAdmin()) {
            boolean allowed = dataRepository.findEvidencePersonIds(sourceId).stream()
                    .anyMatch(personId -> scopeRepository.existsByUserIdAndPersonIdAndScopeStatus(
                            principal.userId(), personId, 1));
            if (!allowed) {
                throw new BusinessException(HttpStatus.FORBIDDEN, ErrorCode.ACCESS_DENIED, "无权访问该来源证据");
            }
        }
        return new EvidenceResponse(
                evidence.sourceRef(), evidence.fileName(), evidence.sheetName(), evidence.sourceRowNumber(),
                evidence.columnName(), evidence.cellReference(), redactionService.redact(evidence.originalText()),
                evidence.sourceLevel(), evidence.sourceDate(), evidence.sourceLocator());
    }

    private CustomerSummaryResponse summary(Long personId) {
        return dataRepository.findSummary(personId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, "客户不存在"));
    }

    private boolean isPopulated(Map<String, Object> dimension) {
        return dimension.values().stream().anyMatch(value -> value instanceof List<?> list && !list.isEmpty());
    }
}
