package com.privatebank.agent.application.kyc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.agent.domain.kyc.KycCustomerData;
import com.privatebank.agent.domain.kyc.KycGraphRelationship;
import com.privatebank.agent.domain.kyc.KycMaskedInput;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.temporal.TemporalAccessor;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builds the only customer-data object permitted to cross the KYC model boundary.
 * Raw records remain process-local. Direct identifiers become stable runtime aliases,
 * precise contact/location data is removed, and non-identifying business semantics are
 * retained so new record types do not silently disappear at the model boundary.
 */
@Service
public class KycDataMaskingService {

    private static final Pattern CONTROLLED_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,79}");
    private static final Pattern EMAIL = Pattern.compile("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?86[- ]?)?1[3-9]\\d{9}(?!\\d)");
    private static final Pattern ID_NUMBER = Pattern.compile("(?<![0-9A-Z])\\d{17}[0-9Xx](?![0-9A-Z])");
    private static final Pattern BANK_ACCOUNT = Pattern.compile("(?<!\\d)\\d{16,19}(?!\\d)");
    private static final Pattern PRECISE_ADDRESS = Pattern.compile(
            ".*(?:[\\p{IsHan}]{2,12}(?:路|街|巷|弄)\\d*号?|\\d+号|栋|幢|单元|室|大厦|小区|公寓).*");
    private static final Pattern ORGANIZATION_IN_TEXT = Pattern.compile(
            "[\\p{IsHan}A-Za-z0-9·&（）()_-]{2,40}(?:股份有限公司|有限公司|公司|大学|学院|学校|银行|基金会|协会|委员会|研究院|实验室)");
    private static final Set<String> DIRECT_IDENTIFIER_KEYS = Set.of(
            "fullname", "displayname", "membername", "protectedalias", "enterprisename",
            "organizationname", "counterpartname", "activityname", "partnername", "publishername",
            "stockcode", "email", "phone", "mobile", "idnumber", "accountnumber", "bankaccount");
    private static final Set<String> METADATA_KEYS = Set.of(
            "createdat", "updatedat", "createtime", "updatetime", "personid", "customerid", "sourceid");

    private final ObjectMapper objectMapper;
    private final KycSemanticProjectionService semanticProjectionService;
    private final KycInputSafetyValidator inputSafetyValidator;

    public KycDataMaskingService(ObjectMapper objectMapper) {
        this(objectMapper, new KycSemanticProjectionService(), new KycInputSafetyValidator());
    }

    KycDataMaskingService(
            ObjectMapper objectMapper,
            KycSemanticProjectionService semanticProjectionService,
            KycInputSafetyValidator inputSafetyValidator) {
        this.objectMapper = objectMapper;
        this.semanticProjectionService = semanticProjectionService;
        this.inputSafetyValidator = inputSafetyValidator;
    }

    public KycMaskedInput mask(KycCustomerData data) {
        return mask(data, KycRuntimeSupplement.empty());
    }

    public KycMaskedInput mask(KycCustomerData data, KycRuntimeSupplement supplement) {
        MaskingContext context = new MaskingContext();
        registerAliases(data, context);
        collectProhibitedTerms(data, context.prohibitedTerms);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractVersion", "kyc-input.v4");
        payload.put("person", person(data, context));
        payload.put("enterprise", enterprise(data, context));
        payload.put("family", family(data, context));
        payload.put("social", social(data, context));
        payload.put("relationshipGraph", relationshipGraph(data.graphRelationships(), context));
        if (supplement != null && !supplement.signals().isEmpty()) {
            payload.put("managerSupplement", Map.of("signals", supplement.signals()));
        }
        inputSafetyValidator.validate(payload, context.prohibitedTerms);

        return new KycMaskedInput(payload, context.evidenceReferences, context.prohibitedTerms, sha256(payload));
    }

    private Map<String, Object> person(KycCustomerData data, MaskingContext context) {
        Map<String, Object> person = new LinkedHashMap<>();
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("personAlias", "P-1");
        putControlled(customer, "personType", data.summary().personType());
        putControlled(customer, "verificationStatus", data.summary().verificationStatus());
        putControlled(customer, "riskLevel", data.summary().riskLevel());
        person.put("customer", customer);
        person.put("profile", profile(data.profile(), context));
        person.put("careers", careers(data.careers(), context));
        person.put("riskPreferences", records(data.riskPreferences(), context,
                Field.code("riskLevel"), Field.number("maxDrawdown"), Field.text("investmentHorizon"),
                Field.text("liquidityRequirement"), Field.text("actualPreference"),
                Field.code("verificationStatus")));
        person.put("financialFacts", financialFacts(data.financialFacts(), context));
        person.put("holdings", holdings(data.holdings(), context));
        person.put("financialEvents", records(data.financialEvents(), context,
                Field.code("eventType"), Field.date("eventDate"), Field.number("amount"),
                Field.currency("currencyCode"), Field.code("verificationStatus")));
        person.put("serviceRecords", records(data.serviceRecords(), context,
                Field.code("serviceType"), Field.number("serviceYears"), Field.code("serviceFrequency"),
                Field.code("verificationStatus")));
        person.put("interactionSignals", interactionSignals(data.interactionNotes(), context));
        return person;
    }

    private Map<String, Object> profile(Map<String, Object> source, MaskingContext context) {
        Map<String, Object> profile = recordBase(source, context);
        putNumber(profile, "birthYear", value(source, "birthYear"));
        putControlled(profile, "verificationStatus", value(source, "verificationStatus"));
        return profile;
    }

    private List<Map<String, Object>> careers(List<Map<String, Object>> source, MaskingContext context) {
        return source == null ? List.of() : source.stream().map(record -> {
            Map<String, Object> masked = recordBase(record, context);
            putAlias(masked, "personAlias", "P-1");
            putAlias(masked, "organizationAlias", context.organizationAlias(record));
            putControlled(masked, "roleCategory", semanticProjectionService.roleCategory(
                    value(record, "positionTitle"), value(record, "organizationName")));
            putDate(masked, "startDate", value(record, "startDate"));
            putDate(masked, "endDate", value(record, "endDate"));
            putControlled(masked, "verificationStatus", value(record, "verificationStatus"));
            return masked;
        }).toList();
    }

    private List<Map<String, Object>> financialFacts(List<Map<String, Object>> source, MaskingContext context) {
        return source == null ? List.of() : source.stream().map(record -> {
            Map<String, Object> masked = recordBase(record, context);
            putControlled(masked, "factCategory", value(record, "factCategory"));
            putControlled(masked, "assetCategory", semanticProjectionService.assetCategory(value(record, "assetType")));
            putNumber(masked, "amount", value(record, "amount"));
            putCurrency(masked, "currencyCode", value(record, "currencyCode"));
            putNumber(masked, "percentage", value(record, "percentage"));
            putBoolean(masked, "estimateFlag", value(record, "estimateFlag"));
            putDate(masked, "effectiveDate", value(record, "effectiveDate"));
            putControlled(masked, "verificationStatus", value(record, "verificationStatus"));
            return masked;
        }).toList();
    }

    private List<Map<String, Object>> holdings(List<Map<String, Object>> source, MaskingContext context) {
        return source == null ? List.of() : source.stream().map(record -> {
            Map<String, Object> masked = recordBase(record, context);
            putControlled(masked, "productCategory", semanticProjectionService.assetCategory(value(record, "productType")));
            putNumber(masked, "amount", value(record, "amount"));
            putCurrency(masked, "currencyCode", value(record, "currencyCode"));
            putDate(masked, "maturityDate", value(record, "maturityDate"));
            putControlled(masked, "riskLevel", value(record, "riskLevel"));
            putControlled(masked, "verificationStatus", value(record, "verificationStatus"));
            return masked;
        }).toList();
    }

    private List<Map<String, Object>> interactionSignals(List<Map<String, Object>> source, MaskingContext context) {
        return source == null ? List.of() : source.stream().map(record -> {
            Map<String, Object> masked = recordBase(record, context);
            putAlias(masked, "personAlias", "P-1");
            putControlled(masked, "noteType", value(record, "noteType"));
            putBoolean(masked, "isExplicitExpression", value(record, "isExplicitExpression"));
            putCodes(masked, "topicCodes", semanticProjectionService.interactionTopics(value(record, "noteText")));
            putControlled(masked, "verificationStatus", value(record, "verificationStatus"));
            return masked;
        }).toList();
    }

    private Map<String, Object> enterprise(KycCustomerData data, MaskingContext context) {
        Map<String, Object> enterprise = new LinkedHashMap<>();
        enterprise.put("relations", enterpriseRelations(data.enterpriseRelations(), context));
        enterprise.put("businesses", enterpriseBusinesses(data.enterpriseBusinesses(), context));
        enterprise.put("financialMetrics", enterpriseFinancialMetrics(data.enterpriseFinancialMetrics(), context));
        enterprise.put("events", enterpriseEvents(data.enterpriseEvents(), context));
        enterprise.put("marketRelations", enterpriseMarketRelations(data.enterpriseMarketRelations(), context));
        return enterprise;
    }

    private List<Map<String, Object>> enterpriseRelations(List<Map<String, Object>> source, MaskingContext context) {
        return source == null ? List.of() : source.stream().map(record -> {
            Map<String, Object> masked = recordBase(record, context);
            putAlias(masked, "personAlias", "P-1");
            putAlias(masked, "enterpriseAlias", context.enterpriseAlias(record));
            putControlled(masked, "relationType", value(record, "relationType"));
            putControlled(masked, "roleCategory", semanticProjectionService.roleCategory(value(record, "title")));
            putNumber(masked, "ownershipPercentage", value(record, "ownershipPercentage"));
            putNumber(masked, "votingRightPercentage", value(record, "votingRightPercentage"));
            putBoolean(masked, "isCoreRelation", value(record, "isCoreRelation"));
            putDate(masked, "validFrom", value(record, "validFrom"));
            putDate(masked, "validTo", value(record, "validTo"));
            putControlled(masked, "industryCategory", semanticProjectionService.industryCategory(value(record, "industryName")));
            putNumber(masked, "employeeCount", value(record, "employeeCount"));
            putControlled(masked, "verificationStatus", value(record, "verificationStatus"));
            return masked;
        }).toList();
    }

    private List<Map<String, Object>> enterpriseBusinesses(List<Map<String, Object>> source, MaskingContext context) {
        return source == null ? List.of() : source.stream().map(record -> {
            Map<String, Object> masked = recordBase(record, context);
            putAlias(masked, "enterpriseAlias", context.enterpriseAlias(record));
            putCodes(masked, "businessCategories", semanticProjectionService.businessCategories(
                    value(record, "businessLine"), value(record, "businessDescription")));
            putControlled(masked, "verificationStatus", value(record, "verificationStatus"));
            return masked;
        }).toList();
    }

    private List<Map<String, Object>> enterpriseFinancialMetrics(
            List<Map<String, Object>> source, MaskingContext context) {
        return source == null ? List.of() : source.stream().map(record -> {
            Map<String, Object> masked = recordBase(record, context);
            putAlias(masked, "enterpriseAlias", context.enterpriseAlias(record));
            putControlled(masked, "reportingPeriod", value(record, "reportingPeriod"));
            putControlled(masked, "metricName", value(record, "metricName"));
            putNumber(masked, "metricValue", value(record, "metricValue"));
            putCurrency(masked, "unitName", value(record, "unitName"));
            putControlled(masked, "verificationStatus", value(record, "verificationStatus"));
            return masked;
        }).toList();
    }

    private List<Map<String, Object>> enterpriseEvents(List<Map<String, Object>> source, MaskingContext context) {
        return source == null ? List.of() : source.stream().map(record -> {
            Map<String, Object> masked = recordBase(record, context);
            putAlias(masked, "enterpriseAlias", context.enterpriseAlias(record));
            putControlled(masked, "eventType", value(record, "eventType"));
            putDate(masked, "eventDate", value(record, "eventDate"));
            putControlled(masked, "riskLevel", value(record, "riskLevel"));
            putCodes(masked, "eventSignals", semanticProjectionService.enterpriseEventSignals(value(record, "eventDescription")));
            putControlled(masked, "verificationStatus", value(record, "verificationStatus"));
            return masked;
        }).toList();
    }

    private List<Map<String, Object>> enterpriseMarketRelations(List<Map<String, Object>> source, MaskingContext context) {
        return source == null ? List.of() : source.stream().map(record -> {
            Map<String, Object> masked = recordBase(record, context);
            putAlias(masked, "enterpriseAlias", context.enterpriseAlias(record));
            putAlias(masked, "counterpartyAlias", context.counterpartyAlias(record));
            putControlled(masked, "relationType", value(record, "relationType"));
            putControlled(masked, "verificationStatus", value(record, "verificationStatus"));
            return masked;
        }).toList();
    }

    private Map<String, Object> family(KycCustomerData data, MaskingContext context) {
        Map<String, Object> family = new LinkedHashMap<>();
        family.put("members", familyMembers(data.familyMembers(), context));
        family.put("relations", familyRelations(data.familyRelations(), context));
        family.put("successionArrangements", successionArrangements(data.successionArrangements(), context));
        return family;
    }

    private List<Map<String, Object>> familyMembers(List<Map<String, Object>> source, MaskingContext context) {
        return source == null ? List.of() : source.stream().map(record -> {
            Map<String, Object> masked = recordBase(record, context);
            putAlias(masked, "familyAlias", context.familyAlias(record));
            putControlled(masked, "publicDisclosureLevel", value(record, "publicDisclosureLevel"));
            putControlled(masked, "verificationStatus", value(record, "verificationStatus"));
            return masked;
        }).toList();
    }

    private List<Map<String, Object>> familyRelations(List<Map<String, Object>> source, MaskingContext context) {
        return source == null ? List.of() : source.stream().map(record -> {
            Map<String, Object> masked = recordBase(record, context);
            putAlias(masked, "personAlias", "P-1");
            putAlias(masked, "familyAlias", context.familyAlias(record));
            putControlled(masked, "relationType", value(record, "relationType"));
            putControlled(masked, "publicDisclosureLevel", value(record, "publicDisclosureLevel"));
            putControlled(masked, "verificationStatus", value(record, "verificationStatus"));
            return masked;
        }).toList();
    }

    private List<Map<String, Object>> successionArrangements(List<Map<String, Object>> source, MaskingContext context) {
        return source == null ? List.of() : source.stream().map(record -> {
            Map<String, Object> masked = recordBase(record, context);
            putAlias(masked, "personAlias", "P-1");
            putAlias(masked, "enterpriseAlias", context.enterpriseAlias(record));
            putControlled(masked, "arrangementStatus", value(record, "arrangementStatus"));
            putControlled(masked, "governanceCategory", semanticProjectionService.governanceCategory(
                    value(record, "governanceModel"), value(record, "arrangementDescription")));
            putControlled(masked, "verificationStatus", value(record, "verificationStatus"));
            return masked;
        }).toList();
    }

    private Map<String, Object> social(KycCustomerData data, MaskingContext context) {
        Map<String, Object> social = new LinkedHashMap<>();
        social.put("relations", socialRelations(data.socialRelations(), context));
        social.put("activities", socialActivities(data.socialActivities(), context));
        social.put("publicReputation", publicReputation(data.publicReputations(), context));
        social.put("reputationRisks", reputationRisks(data.reputationRisks(), context));
        return social;
    }

    private List<Map<String, Object>> socialRelations(List<Map<String, Object>> source, MaskingContext context) {
        return source == null ? List.of() : source.stream().map(record -> {
            Map<String, Object> masked = recordBase(record, context);
            putAlias(masked, "personAlias", "P-1");
            putAlias(masked, "organizationAlias", context.organizationAlias(record));
            putControlled(masked, "organizationType", value(record, "organizationType"));
            putControlled(masked, "relationType", value(record, "relationType"));
            putControlled(masked, "roleCategory", semanticProjectionService.roleCategory(value(record, "roleTitle")));
            putDate(masked, "validFrom", value(record, "validFrom"));
            putDate(masked, "validTo", value(record, "validTo"));
            putControlled(masked, "verificationStatus", value(record, "verificationStatus"));
            return masked;
        }).toList();
    }

    private List<Map<String, Object>> socialActivities(List<Map<String, Object>> source, MaskingContext context) {
        return source == null ? List.of() : source.stream().map(record -> {
            Map<String, Object> masked = recordBase(record, context);
            putAlias(masked, "personAlias", "P-1");
            putControlled(masked, "activityType", value(record, "activityType"));
            putDate(masked, "activityDate", value(record, "activityDate"));
            putNumber(masked, "amount", value(record, "amount"));
            putCurrency(masked, "currencyCode", value(record, "currencyCode"));
            putCodes(masked, "activitySignals", semanticProjectionService.activitySignals(value(record, "activityDescription")));
            putControlled(masked, "verificationStatus", value(record, "verificationStatus"));
            return masked;
        }).toList();
    }

    private List<Map<String, Object>> publicReputation(List<Map<String, Object>> source, MaskingContext context) {
        return source == null ? List.of() : source.stream().map(record -> {
            Map<String, Object> masked = recordBase(record, context);
            putAlias(masked, "personAlias", "P-1");
            putControlled(masked, "reputationType", value(record, "reputationType"));
            putDate(masked, "publicationDate", value(record, "publicationDate"));
            putCodes(masked, "reputationSignals", semanticProjectionService.reputationSignals(
                    value(record, "title"), value(record, "description")));
            putControlled(masked, "verificationStatus", value(record, "verificationStatus"));
            return masked;
        }).toList();
    }

    private List<Map<String, Object>> reputationRisks(List<Map<String, Object>> source, MaskingContext context) {
        return source == null ? List.of() : source.stream().map(record -> {
            Map<String, Object> masked = recordBase(record, context);
            putAlias(masked, "personAlias", "P-1");
            putControlled(masked, "riskLevel", value(record, "riskLevel"));
            putDate(masked, "eventDate", value(record, "eventDate"));
            putCodes(masked, "riskCategories", semanticProjectionService.reputationRiskCategories(
                    value(record, "riskTopic"), value(record, "riskDescription")));
            putControlled(masked, "verificationStatus", value(record, "verificationStatus"));
            return masked;
        }).toList();
    }

    private Map<String, Object> relationshipGraph(
            List<KycGraphRelationship> relationships, MaskingContext context) {
        if (relationships == null || relationships.isEmpty()) {
            return Map.of(
                    "available", false,
                    "relationshipCount", 0,
                    "evidenceRefs", List.of(),
                    "relationships", List.of());
        }
        List<Map<String, Object>> projected = relationships.stream().map(relationship -> {
            Map<String, Object> masked = new LinkedHashMap<>();
            if (relationship.sourceId() != null) {
                masked.put("sourceRef", context.sourceReference(relationship.sourceId()));
            }
            masked.put("evidenceOrigin", "NEO4J_RELATIONSHIP");
            putAlias(masked, "startAlias", context.graphAlias(
                    relationship.startNodeType(), relationship.startNodeId(), relationship.startIsCustomer()));
            putControlled(masked, "startType", relationship.startNodeType());
            putControlled(masked, "relationType", relationship.relationType());
            putAlias(masked, "endAlias", context.graphAlias(
                    relationship.endNodeType(), relationship.endNodeId(), relationship.endIsCustomer()));
            putControlled(masked, "endType", relationship.endNodeType());
            putControlled(masked, "verificationStatus", relationship.verificationStatus());
            putNumber(masked, "confidence", relationship.confidence());
            putNumber(masked, "distance", relationship.distance());
            masked.put("pathScope", relationship.distance() > 1
                    ? "TWO_HOP" : "DIRECT");
            return masked;
        }).toList();
        List<String> evidenceRefs = projected.stream()
                .map(item -> item.get("sourceRef"))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .distinct()
                .toList();
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("available", true);
        graph.put("relationshipCount", projected.size());
        graph.put("evidenceRefs", evidenceRefs);
        graph.put("relationships", projected);
        return graph;
    }

    private List<Map<String, Object>> records(
            List<Map<String, Object>> source, MaskingContext context, Field... fields) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        return source.stream().map(record -> record(record, context, fields)).toList();
    }

    private Map<String, Object> record(Map<String, Object> source, MaskingContext context, Field... fields) {
        Map<String, Object> masked = recordBase(source, context);
        for (Field field : fields) {
            field.add(masked, value(source, field.keys()));
        }
        return masked;
    }

    private Map<String, Object> recordBase(Map<String, Object> source, MaskingContext context) {
        Map<String, Object> masked = new LinkedHashMap<>();
        Long sourceId = longValue(value(source, "sourceId"));
        if (sourceId != null) {
            masked.put("sourceRef", context.sourceReference(sourceId));
        }
        Map<String, Object> attributes = safeBusinessAttributes(source, context);
        if (!attributes.isEmpty()) {
            masked.put("businessAttributes", attributes);
        }
        return masked;
    }

    private Map<String, Object> safeBusinessAttributes(Map<String, Object> source, MaskingContext context) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (source == null) {
            return attributes;
        }
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String normalizedKey = normalize(entry.getKey());
            if (DIRECT_IDENTIFIER_KEYS.contains(normalizedKey) || METADATA_KEYS.contains(normalizedKey)
                    || normalizedKey.endsWith("id")) {
                continue;
            }
            Object safe = safeValue(entry.getValue(), entry.getKey(), context, 0);
            if (safe != null) {
                attributes.put(entry.getKey(), safe);
            }
        }
        return attributes;
    }

    private Object safeValue(Object raw, String fieldName, MaskingContext context, int depth) {
        if (raw == null || depth > 4) {
            return null;
        }
        if (raw instanceof Number || raw instanceof Boolean || raw instanceof TemporalAccessor || raw instanceof Date) {
            return raw;
        }
        if (raw instanceof String text) {
            String normalizedField = normalize(fieldName);
            if (DIRECT_IDENTIFIER_KEYS.contains(normalizedField)) {
                return null;
            }
            if (normalizedField.contains("address") || normalizedField.contains("headquarters")) {
                return coarseLocation(text);
            }
            String sanitized = context.redact(text);
            sanitized = EMAIL.matcher(sanitized).replaceAll("[EMAIL_REDACTED]");
            sanitized = PHONE.matcher(sanitized).replaceAll("[PHONE_REDACTED]");
            sanitized = ID_NUMBER.matcher(sanitized).replaceAll("[ID_REDACTED]");
            sanitized = BANK_ACCOUNT.matcher(sanitized).replaceAll("[ACCOUNT_REDACTED]");
            sanitized = ORGANIZATION_IN_TEXT.matcher(sanitized).replaceAll("O-REDACTED");
            if (PRECISE_ADDRESS.matcher(sanitized).matches()) {
                sanitized = coarseLocation(sanitized);
            }
            sanitized = sanitized.trim();
            return sanitized.isEmpty() ? null : sanitized.substring(0, Math.min(600, sanitized.length()));
        }
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() instanceof String key) {
                    Object safe = safeValue(entry.getValue(), key, context, depth + 1);
                    if (safe != null) {
                        nested.put(key, safe);
                    }
                }
            }
            return nested.isEmpty() ? null : nested;
        }
        if (raw instanceof Iterable<?> iterable) {
            List<Object> nested = new java.util.ArrayList<>();
            for (Object item : iterable) {
                Object safe = safeValue(item, fieldName, context, depth + 1);
                if (safe != null && nested.size() < 100) {
                    nested.add(safe);
                }
            }
            return nested.isEmpty() ? null : nested;
        }
        return null;
    }

    private String coarseLocation(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        java.util.regex.Matcher city = Pattern.compile("([\\p{IsHan}]{2,12}市)").matcher(raw);
        if (city.find()) {
            return city.group(1);
        }
        java.util.regex.Matcher province = Pattern.compile("([\\p{IsHan}]{2,12}(?:省|自治区|特别行政区))").matcher(raw);
        if (province.find()) {
            return province.group(1);
        }
        return PRECISE_ADDRESS.matcher(raw).matches() ? "LOCATION_REDACTED" : raw;
    }

    private void collectProhibitedTerms(KycCustomerData data, Set<String> terms) {
        addTerm(terms, data.summary().fullName());
        addTerm(terms, data.summary().displayName());
        collectTerms(data.profile(), terms, "email", "phone", "mobile", "address", "idNumber");
        collectTerms(data.careers(), terms, "organizationName");
        collectTerms(data.enterpriseRelations(), terms, "enterpriseName", "stockCode", "headquarters");
        collectTerms(data.familyMembers(), terms, "memberName", "protectedAlias");
        collectTerms(data.socialRelations(), terms, "organizationName");
        collectTerms(data.socialActivities(), terms, "activityName", "partnerName");
        collectTerms(data.publicReputations(), terms, "publisherName");
        collectTerms(data.enterpriseMarketRelations(), terms, "counterpartName");
    }

    private void registerAliases(KycCustomerData data, MaskingContext context) {
        context.registerEntity(data.summary().fullName(), "P-1", false);
        context.registerEntity(data.summary().displayName(), "P-1", false);
        registerRecordAliases(data.enterpriseRelations(), context, AliasKind.ENTERPRISE);
        registerRecordAliases(data.careers(), context, AliasKind.ORGANIZATION);
        registerRecordAliases(data.enterpriseMarketRelations(), context, AliasKind.COUNTERPARTY);
        registerRecordAliases(data.familyMembers(), context, AliasKind.FAMILY);
        registerRecordAliases(data.socialRelations(), context, AliasKind.ORGANIZATION);
        registerRecordAliases(data.socialActivities(), context, AliasKind.ACTIVITY_PARTY);
        registerRecordAliases(data.publicReputations(), context, AliasKind.PUBLISHER);
    }

    private void registerRecordAliases(
            List<Map<String, Object>> records, MaskingContext context, AliasKind kind) {
        if (records == null) {
            return;
        }
        for (Map<String, Object> record : records) {
            switch (kind) {
                case ENTERPRISE -> {
                    String alias = context.enterpriseAlias(record);
                    context.registerEntity(stringValue(value(record, "enterpriseName")), alias, true);
                    context.registerEntity(stringValue(value(record, "stockCode")), alias, false);
                }
                case FAMILY -> {
                    String alias = context.familyAlias(record);
                    context.registerEntity(stringValue(value(record, "memberName")), alias, false);
                    context.registerEntity(stringValue(value(record, "protectedAlias")), alias, false);
                }
                case COUNTERPARTY -> context.registerEntity(
                        stringValue(value(record, "counterpartName")), context.counterpartyAlias(record), true);
                case ORGANIZATION -> context.registerEntity(
                        stringValue(value(record, "organizationName")), context.organizationAlias(record), true);
                case ACTIVITY_PARTY -> {
                    context.registerEntity(stringValue(value(record, "activityName")), context.activityPartyAlias(record), true);
                    context.registerEntity(stringValue(value(record, "partnerName")), context.activityPartyAlias(record), true);
                }
                case PUBLISHER -> context.registerEntity(
                        stringValue(value(record, "publisherName")), context.publisherAlias(record), true);
            }
        }
    }

    private String stringValue(Object value) {
        return value instanceof String text ? text : null;
    }

    private enum AliasKind {
        ENTERPRISE, FAMILY, COUNTERPARTY, ORGANIZATION, ACTIVITY_PARTY, PUBLISHER
    }

    private void collectTerms(Map<String, Object> record, Set<String> terms, String... keys) {
        if (record == null) {
            return;
        }
        for (String key : keys) {
            addTerm(terms, value(record, key));
        }
    }

    private void collectTerms(List<Map<String, Object>> records, Set<String> terms, String... keys) {
        if (records == null) {
            return;
        }
        for (Map<String, Object> record : records) {
            collectTerms(record, terms, keys);
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

    private void putAlias(Map<String, Object> target, String key, String alias) {
        if (alias != null) {
            target.put(key, alias);
        }
    }

    private void putControlled(Map<String, Object> target, String key, Object raw) {
        if (raw instanceof String text && CONTROLLED_CODE.matcher(text).matches()) {
            target.put(key, text);
        }
    }

    private void putCodes(Map<String, Object> target, String key, List<String> codes) {
        if (codes != null && !codes.isEmpty()) {
            target.put(key, codes);
        }
    }

    private void putNumber(Map<String, Object> target, String key, Object raw) {
        if (raw instanceof Number number) {
            target.put(key, number);
        }
    }

    private void putBoolean(Map<String, Object> target, String key, Object raw) {
        if (raw instanceof Boolean flag) {
            target.put(key, flag);
        }
    }

    private void putDate(Map<String, Object> target, String key, Object raw) {
        if (raw instanceof TemporalAccessor || raw instanceof Date) {
            target.put(key, raw);
        } else if (raw instanceof String text && text.matches("[0-9]{4}(-[0-9]{2}(-[0-9]{2})?)?")) {
            target.put(key, text);
        }
    }

    private void putCurrency(Map<String, Object> target, String key, Object raw) {
        if (raw instanceof String text && text.matches("[A-Z]{3}(_[0-9]+[A-Z])?")) {
            target.put(key, text);
        }
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

    private record Field(String name, Type type, String... keys) {
        private static Field code(String name) {
            return new Field(name, Type.CODE, name);
        }

        private static Field number(String name) {
            return new Field(name, Type.NUMBER, name);
        }

        private static Field date(String name) {
            return new Field(name, Type.DATE, name);
        }

        private static Field currency(String name) {
            return new Field(name, Type.CURRENCY, name);
        }

        private static Field text(String name) {
            return new Field(name, Type.TEXT, name);
        }

        private void add(Map<String, Object> target, Object raw) {
            switch (type) {
                case CODE -> putCode(target, name, raw);
                case NUMBER -> putNumberValue(target, name, raw);
                case DATE -> putDateValue(target, name, raw);
                case CURRENCY -> putCurrencyValue(target, name, raw);
                case TEXT -> putTextValue(target, name, raw);
            }
        }

        private static void putCode(Map<String, Object> target, String key, Object raw) {
            if (raw instanceof String text && CONTROLLED_CODE.matcher(text).matches()) {
                target.put(key, text);
            }
        }

        private static void putNumberValue(Map<String, Object> target, String key, Object raw) {
            if (raw instanceof Number number) {
                target.put(key, number);
            }
        }

        private static void putDateValue(Map<String, Object> target, String key, Object raw) {
            if (raw instanceof TemporalAccessor || raw instanceof Date) {
                target.put(key, raw);
            } else if (raw instanceof String text && text.matches("[0-9]{4}(-[0-9]{2}(-[0-9]{2})?)?")) {
                target.put(key, text);
            }
        }

        private static void putCurrencyValue(Map<String, Object> target, String key, Object raw) {
            if (raw instanceof String text && text.matches("[A-Z]{3}(_[0-9]+[A-Z])?")) {
                target.put(key, text);
            }
        }

        private static void putTextValue(Map<String, Object> target, String key, Object raw) {
            if (raw instanceof String text && !text.isBlank() && text.length() <= 80) {
                target.put(key, text.trim());
            }
        }
    }

    private enum Type {
        CODE, NUMBER, DATE, CURRENCY, TEXT
    }

    private static final class MaskingContext {
        private final Map<Long, String> referencesBySource = new LinkedHashMap<>();
        private final Map<String, Long> evidenceReferences = new LinkedHashMap<>();
        private final Set<String> prohibitedTerms = new LinkedHashSet<>();
        private final Map<String, String> enterpriseAliases = new LinkedHashMap<>();
        private final Map<String, String> familyAliases = new LinkedHashMap<>();
        private final Map<String, String> organizationAliases = new LinkedHashMap<>();
        private final Map<String, String> counterpartAliases = new LinkedHashMap<>();
        private final Map<String, String> graphPersonAliases = new LinkedHashMap<>();
        private final Map<String, String> eventAliases = new LinkedHashMap<>();
        private final Map<String, String> marketSegmentAliases = new LinkedHashMap<>();
        private final Map<String, String> otherGraphAliases = new LinkedHashMap<>();
        private final Map<String, String> redactions = new LinkedHashMap<>();

        private MaskingContext() {
            graphPersonAliases.put("CUSTOMER", "P-1");
        }

        private String sourceReference(Long sourceId) {
            return referencesBySource.computeIfAbsent(sourceId, ignored -> {
                String reference = "SRC-" + (referencesBySource.size() + 1);
                evidenceReferences.put(reference, sourceId);
                return reference;
            });
        }

        private String enterpriseAlias(Map<String, Object> record) {
            return alias(enterpriseAliases, "E", entityKey(record, "enterpriseId", "enterpriseName"));
        }

        private String familyAlias(Map<String, Object> record) {
            return alias(familyAliases, "F", entityKey(record, "familyMemberId", "memberName", "protectedAlias"));
        }

        private String organizationAlias(Map<String, Object> record) {
            return alias(organizationAliases, "O", entityKey(record, "socialOrganizationId", "organizationId", "organizationName"));
        }

        private String counterpartyAlias(Map<String, Object> record) {
            return alias(counterpartAliases, "C", entityKey(record, "counterpartId", "counterpartName"));
        }

        private String activityPartyAlias(Map<String, Object> record) {
            return alias(organizationAliases, "O", entityKey(record, "activityName", "partnerName"));
        }

        private String publisherAlias(Map<String, Object> record) {
            return alias(organizationAliases, "O", entityKey(record, "publisherName"));
        }

        private void registerEntity(String raw, String alias, boolean organization) {
            if (raw == null || raw.isBlank() || alias == null) {
                return;
            }
            addRedaction(raw.trim(), alias);
            String withoutParentheses = raw.replaceAll("[（(].*?[）)]", "").trim();
            addRedaction(withoutParentheses, alias);
            if (organization) {
                String shortName = withoutParentheses
                        .replaceAll("(?:股份)?有限公司$", "")
                        .replaceAll("控股$", "")
                        .trim();
                addRedaction(shortName, alias);
            }
        }

        private void addRedaction(String raw, String alias) {
            if (raw != null && raw.length() >= 2) {
                redactions.putIfAbsent(raw, alias);
            }
        }

        private String redact(String source) {
            String sanitized = source;
            List<Map.Entry<String, String>> entries = redactions.entrySet().stream()
                    .sorted((left, right) -> Integer.compare(right.getKey().length(), left.getKey().length()))
                    .toList();
            for (Map.Entry<String, String> entry : entries) {
                sanitized = Pattern.compile(Pattern.quote(entry.getKey()), Pattern.CASE_INSENSITIVE)
                        .matcher(sanitized)
                        .replaceAll(java.util.regex.Matcher.quoteReplacement(entry.getValue()));
            }
            return sanitized;
        }

        private String graphAlias(String nodeType, String nodeId, boolean customer) {
            if (customer) {
                return "P-1";
            }
            String type = nodeType == null ? "" : nodeType;
            String businessId = graphBusinessId(nodeId);
            return switch (type) {
                case "PERSON" -> alias(graphPersonAliases, "P", businessId);
                case "ENTERPRISE" -> alias(enterpriseAliases, "E", qualifiedKey("enterpriseId", businessId));
                case "FAMILY_MEMBER" -> alias(familyAliases, "F", qualifiedKey("familyMemberId", businessId));
                case "FAMILY_PROFILE" -> alias(familyAliases, "F", qualifiedKey("familyProfileId", businessId));
                case "ORGANIZATION" -> alias(organizationAliases, "O", qualifiedKey("socialOrganizationId", businessId));
                case "EVENT" -> alias(eventAliases, "V", nodeId);
                case "MARKET_SEGMENT" -> alias(marketSegmentAliases, "M", nodeId);
                default -> alias(otherGraphAliases, "N", nodeId);
            };
        }

        private String graphBusinessId(String nodeId) {
            if (nodeId == null || nodeId.isBlank()) {
                return null;
            }
            int firstSeparator = nodeId.indexOf(':');
            if (firstSeparator > 0 && firstSeparator == nodeId.lastIndexOf(':')
                    && firstSeparator < nodeId.length() - 1) {
                return nodeId.substring(firstSeparator + 1);
            }
            return nodeId;
        }

        private String qualifiedKey(String key, String value) {
            return value == null ? null : key + ":" + value;
        }

        private String entityKey(Map<String, Object> record, String... keys) {
            if (record == null) {
                return null;
            }
            for (String key : keys) {
                Object candidate = lookup(record, key);
                if (candidate instanceof Number number) {
                    return key + ":" + number.longValue();
                }
                if (candidate instanceof String text && !text.isBlank()) {
                    return key + ":" + text;
                }
            }
            return null;
        }

        private Object lookup(Map<String, Object> record, String key) {
            Object direct = record.get(key);
            if (direct != null) {
                return direct;
            }
            String normalizedKey = key.replace("_", "").toLowerCase(Locale.ROOT);
            return record.entrySet().stream()
                    .filter(entry -> entry.getKey().replace("_", "").toLowerCase(Locale.ROOT).equals(normalizedKey))
                    .map(Map.Entry::getValue)
                    .filter(value -> value != null)
                    .findFirst()
                    .orElse(null);
        }

        private String alias(Map<String, String> aliases, String prefix, String key) {
            if (key == null) {
                return null;
            }
            return aliases.computeIfAbsent(key, ignored -> prefix + "-" + (aliases.size() + 1));
        }
    }
}
