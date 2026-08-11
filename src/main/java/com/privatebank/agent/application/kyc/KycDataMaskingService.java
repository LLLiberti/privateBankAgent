package com.privatebank.agent.application.kyc;

import com.privatebank.agent.domain.kyc.KycCustomerData;
import com.privatebank.agent.domain.kyc.KycMaskedInput;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.temporal.TemporalAccessor;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Transforms raw 人企家社 records into a strict allow-list projection before any model call.
 * Direct identifiers and all free-text source fields are intentionally absent from this projection.
 */
@Service
@RequiredArgsConstructor
public class KycDataMaskingService {

    private final ObjectMapper objectMapper;

    public KycMaskedInput mask(KycCustomerData data) {
        MaskingContext context = new MaskingContext();
        collectProhibitedTerms(data, context.prohibitedTerms);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractVersion", "kyc-input.v1");
        payload.put("person", person(data, context));
        payload.put("enterprise", enterprise(data, context));
        payload.put("family", family(data, context));
        payload.put("social", social(data, context));

        return new KycMaskedInput(payload, context.evidenceReferences, context.prohibitedTerms, sha256(payload));
    }

    private Map<String, Object> person(KycCustomerData data, MaskingContext context) {
        Map<String, Object> person = new LinkedHashMap<>();
        Map<String, Object> customer = new LinkedHashMap<>();
        put(customer, "personType", data.summary().personType());
        put(customer, "verificationStatus", data.summary().verificationStatus());
        put(customer, "riskLevel", data.summary().riskLevel());
        person.put("customer", customer);
        person.put("profile", record(data.profile(), context,
                field("gender"), field("birthYear"), field("maritalStatus"), field("educationLevel"),
                field("verificationStatus")));
        person.put("careers", records(data.careers(), context,
                field("positionTitle"), field("startDate"), field("endDate"), field("verificationStatus")));
        person.put("riskPreferences", records(data.riskPreferences(), context,
                field("riskLevel"), field("maxDrawdown"), field("investmentHorizon"),
                field("liquidityRequirement"), field("verificationStatus")));
        person.put("financialFacts", records(data.financialFacts(), context,
                field("factCategory"), field("assetType"), field("amount"), field("currencyCode"),
                field("percentage"), field("estimateFlag"), field("effectiveDate"), field("verificationStatus")));
        person.put("holdings", records(data.holdings(), context,
                field("productType"), field("amount"), field("currencyCode"), field("maturityDate"),
                field("riskLevel"), field("verificationStatus")));
        person.put("financialEvents", records(data.financialEvents(), context,
                field("eventType"), field("eventDate"), field("amount"), field("currencyCode"),
                field("verificationStatus")));
        person.put("serviceRecords", records(data.serviceRecords(), context,
                field("serviceType"), field("serviceYears"), field("serviceFrequency"), field("verificationStatus")));
        person.put("interactionSignals", records(data.interactionNotes(), context,
                field("noteType"), field("isExplicitExpression"), field("verificationStatus")));
        return person;
    }

    private Map<String, Object> enterprise(KycCustomerData data, MaskingContext context) {
        Map<String, Object> enterprise = new LinkedHashMap<>();
        enterprise.put("relations", records(data.enterpriseRelations(), context,
                field("relationType"), field("title"), field("ownershipPercentage"), field("votingRightPercentage"),
                field("isCoreRelation"), field("validFrom"), field("validTo"), field("industryName"),
                field("employeeCount"), field("verificationStatus")));
        enterprise.put("businesses", records(data.enterpriseBusinesses(), context,
                field("businessLine"), field("verificationStatus")));
        enterprise.put("financialMetrics", records(data.enterpriseFinancialMetrics(), context,
                field("reportingPeriod"), field("metricName"), field("metricValue"), field("unitName"),
                field("verificationStatus")));
        enterprise.put("events", records(data.enterpriseEvents(), context,
                field("eventType"), field("eventDate"), field("riskLevel"), field("verificationStatus")));
        enterprise.put("marketRelations", records(data.enterpriseMarketRelations(), context,
                field("relationType"), field("verificationStatus")));
        return enterprise;
    }

    private Map<String, Object> family(KycCustomerData data, MaskingContext context) {
        Map<String, Object> family = new LinkedHashMap<>();
        family.put("members", records(data.familyMembers(), context,
                field("publicDisclosureLevel"), field("verificationStatus")));
        family.put("relations", records(data.familyRelations(), context,
                field("relationType"), field("publicDisclosureLevel"), field("verificationStatus")));
        family.put("successionArrangements", records(data.successionArrangements(), context,
                field("arrangementStatus"), field("governanceModel"), field("verificationStatus")));
        return family;
    }

    private Map<String, Object> social(KycCustomerData data, MaskingContext context) {
        Map<String, Object> social = new LinkedHashMap<>();
        social.put("relations", records(data.socialRelations(), context,
                field("organizationType"), field("relationType"), field("roleTitle"), field("validFrom"),
                field("validTo"), field("verificationStatus")));
        social.put("activities", records(data.socialActivities(), context,
                field("activityType"), field("activityDate"), field("amount"), field("currencyCode"),
                field("verificationStatus")));
        social.put("publicReputation", records(data.publicReputations(), context,
                field("reputationType"), field("publicationDate"), field("verificationStatus")));
        social.put("reputationRisks", records(data.reputationRisks(), context,
                field("riskLevel"), field("eventDate"), field("verificationStatus")));
        return social;
    }

    private List<Map<String, Object>> records(
            List<Map<String, Object>> source, MaskingContext context, Field... fields) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        return source.stream().map(record -> record(record, context, fields)).toList();
    }

    private Map<String, Object> record(Map<String, Object> source, MaskingContext context, Field... fields) {
        Map<String, Object> masked = new LinkedHashMap<>();
        if (source == null || source.isEmpty()) {
            return masked;
        }
        Long sourceId = longValue(value(source, "sourceId"));
        if (sourceId != null) {
            masked.put("sourceRef", context.sourceReference(sourceId));
        }
        for (Field field : fields) {
            put(masked, field.name(), value(source, field.keys()));
        }
        return masked;
    }

    private void collectProhibitedTerms(KycCustomerData data, Set<String> terms) {
        addTerm(terms, data.summary().fullName());
        addTerm(terms, data.summary().displayName());
        collectTerms(data.careers(), terms, "organizationName");
        collectTerms(data.enterpriseRelations(), terms, "enterpriseName", "stockCode", "headquarters");
        collectTerms(data.familyMembers(), terms, "memberName", "protectedAlias");
        collectTerms(data.socialRelations(), terms, "organizationName");
        collectTerms(data.socialActivities(), terms, "activityName", "partnerName");
        collectTerms(data.publicReputations(), terms, "title", "publisherName");
        collectTerms(data.enterpriseMarketRelations(), terms, "counterpartName");
    }

    private void collectTerms(List<Map<String, Object>> records, Set<String> terms, String... keys) {
        if (records == null) {
            return;
        }
        for (Map<String, Object> record : records) {
            for (String key : keys) {
                addTerm(terms, value(record, key));
            }
        }
    }

    private void addTerm(Set<String> terms, Object value) {
        if (value instanceof String text && text.trim().length() >= 2 && text.length() <= 255) {
            terms.add(text.trim());
        }
    }

    private Object value(Map<String, Object> values, String... keys) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        for (String key : keys) {
            Object direct = values.get(key);
            if (direct != null) {
                return direct;
            }
            String normalizedKey = normalize(key);
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                if (normalize(entry.getKey()).equals(normalizedKey) && entry.getValue() != null) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    private String normalize(String key) {
        return key.replace("_", "").toLowerCase(Locale.ROOT);
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private void put(Map<String, Object> target, String key, Object value) {
        if (isSafeScalar(value)) {
            target.put(key, value);
        }
    }

    private boolean isSafeScalar(Object value) {
        return value instanceof String || value instanceof Number || value instanceof Boolean
                || value instanceof TemporalAccessor || value instanceof BigDecimal;
    }

    private Field field(String name) {
        return new Field(name, name);
    }

    private String sha256(Map<String, Object> payload) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(payload);
            return toHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("无法生成 KYC 脱敏输入校验值", exception);
        }
    }

    private String toHex(byte[] value) {
        StringBuilder builder = new StringBuilder(value.length * 2);
        for (byte item : value) {
            builder.append(String.format("%02x", item));
        }
        return builder.toString();
    }

    private record Field(String name, String... keys) {
    }

    private static final class MaskingContext {
        private final Map<Long, String> referencesBySource = new LinkedHashMap<>();
        private final Map<String, Long> evidenceReferences = new LinkedHashMap<>();
        private final Set<String> prohibitedTerms = new LinkedHashSet<>();

        private String sourceReference(Long sourceId) {
            return referencesBySource.computeIfAbsent(sourceId, ignored -> {
                String reference = "SRC-" + (referencesBySource.size() + 1);
                evidenceReferences.put(reference, sourceId);
                return reference;
            });
        }
    }
}
