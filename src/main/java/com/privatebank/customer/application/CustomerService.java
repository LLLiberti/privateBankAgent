package com.privatebank.customer.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.privatebank.auth.domain.UserCustomerScope;
import com.privatebank.auth.mapper.UserCustomerScopeMapper;
import com.privatebank.common.api.PageResponse;
import com.privatebank.common.exception.BusinessException;
import com.privatebank.common.exception.ErrorCode;
import com.privatebank.customer.api.CustomerDetailResponse;
import com.privatebank.customer.api.CustomerPanoramaResponse;
import com.privatebank.customer.api.CustomerSummaryResponse;
import com.privatebank.customer.api.EvidenceResponse;
import com.privatebank.customer.mapper.CustomerDataMapper;
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

    private final CustomerDataMapper dataMapper;
    private final UserCustomerScopeMapper scopeMapper;
    private final CurrentUserService currentUserService;
    private final RedactionService redactionService;

    public PageResponse<CustomerSummaryResponse> list(
            CurrentUserPrincipal principal, String keyword, int pageNo, int pageSize) {
        List<Long> allowedIds = principal.isSystemAdmin()
                ? null
                : scopeMapper.selectList(Wrappers.<UserCustomerScope>lambdaQuery()
                        .select(UserCustomerScope::getPersonId)
                        .eq(UserCustomerScope::getUserId, principal.userId())
                        .eq(UserCustomerScope::getScopeStatus, 1))
                        .stream().map(UserCustomerScope::getPersonId).toList();
        String normalizedKeyword = keyword == null ? null : keyword.trim();
        long total = dataMapper.countCustomers(normalizedKeyword, allowedIds);
        List<CustomerSummaryResponse> items = dataMapper.findCustomers(
                normalizedKeyword, allowedIds, (pageNo - 1) * pageSize, pageSize);
        return PageResponse.of(items, total, pageNo, pageSize);
    }

    public CustomerDetailResponse detail(CurrentUserPrincipal principal, Long personId) {
        currentUserService.requireCustomerAccess(principal, personId);
        CustomerSummaryResponse summary = summary(personId);
        Map<String, Object> profile = emptyIfNull(dataMapper.findProfile(personId));
        Map<String, Object> dimensionCounts = dataMapper.findDimensionCounts(personId);
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("person", count(dimensionCounts, "person_count"));
        counts.put("enterprise", count(dimensionCounts, "enterprise_count"));
        counts.put("family", count(dimensionCounts, "family_count"));
        counts.put("social", count(dimensionCounts, "social_count"));
        return new CustomerDetailResponse(summary, profile, counts);
    }

    public CustomerPanoramaResponse panorama(
            CurrentUserPrincipal principal, Long personId, LocalDateTime asOfTime) {
        currentUserService.requireCustomerAccess(principal, personId);
        CustomerSummaryResponse summary = summary(personId);

        Map<String, Object> person = new LinkedHashMap<>();
        person.put("summary", summary);
        person.put("profile", emptyIfNull(dataMapper.findProfile(personId)));
        person.put("careers", dataMapper.findCareers(personId));
        person.put("riskPreferences", dataMapper.findRiskPreferences(personId));
        person.put("financialFacts", dataMapper.findFinancialFacts(personId));
        person.put("holdings", dataMapper.findHoldings(personId));
        person.put("events", dataMapper.findEvents(personId));
        person.put("serviceRecords", dataMapper.findServiceRecords(personId));
        person.put("interactionNotes", dataMapper.findInteractionNotes(personId));

        Map<String, Object> enterprise = Map.of("relations", dataMapper.findEnterpriseRelations(personId));

        Map<String, Object> family = new LinkedHashMap<>();
        family.put("members", dataMapper.findFamilyMembers(personId));
        family.put("successionArrangements", dataMapper.findSuccessionArrangements(personId));

        Map<String, Object> social = new LinkedHashMap<>();
        social.put("relations", dataMapper.findSocialRelations(personId));
        social.put("activities", dataMapper.findSocialActivities(personId));
        social.put("publicReputation", dataMapper.findPublicReputation(personId));
        social.put("reputationRisks", dataMapper.findReputationRisks(personId));

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
                dataMapper.findUnresolved(personId));
    }

    public EvidenceResponse evidence(CurrentUserPrincipal principal, Long sourceId) {
        EvidenceResponse evidence = dataMapper.findEvidence(sourceId);
        if (evidence == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, "来源证据不存在");
        }
        if (!principal.isSystemAdmin()) {
            List<Long> evidencePersonIds = dataMapper.findEvidencePersonIds(sourceId);
            boolean allowed = !evidencePersonIds.isEmpty() && scopeMapper.selectCount(
                    Wrappers.<UserCustomerScope>lambdaQuery()
                            .eq(UserCustomerScope::getUserId, principal.userId())
                            .eq(UserCustomerScope::getScopeStatus, 1)
                            .in(UserCustomerScope::getPersonId, evidencePersonIds)) > 0;
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
        CustomerSummaryResponse summary = dataMapper.findSummary(personId);
        if (summary == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, "客户不存在");
        }
        return summary;
    }

    private boolean isPopulated(Map<String, Object> dimension) {
        return dimension.values().stream().anyMatch(value -> value instanceof List<?> list && !list.isEmpty());
    }

    private Map<String, Object> emptyIfNull(Map<String, Object> value) {
        return value == null ? Map.of() : value;
    }

    private long count(Map<String, Object> values, String key) {
        Object value = values == null ? null : values.get(key);
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
