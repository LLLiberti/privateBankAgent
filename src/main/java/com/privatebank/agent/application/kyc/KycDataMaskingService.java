package com.privatebank.agent.application.kyc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.agent.domain.kyc.KycCustomerData;
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
 * Raw records remain process-local: free text becomes controlled semantic codes and
 * all people, enterprises and organizations become stable runtime aliases.
 */
@Service
public class KycDataMaskingService {

    private static final Pattern CONTROLLED_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,79}");

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
        collectProhibitedTerms(data, context.prohibitedTerms);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractVersion", "kyc-input.v2");
        payload.put("person", person(data, context));
        payload.put("enterprise", enterprise(data, context));
        payload.put("family", family(data, context));
        payload.put("social", social(data, context));
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
                Field.code("riskLevel"), Field.number("maxDrawdown"), Field.code("investmentHorizon"),
                Field.code("liquidityRequirement"), Field.code("verificationStatus")));
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
        return masked;
    }

    private void collectProhibitedTerms(KycCustomerData data, Set<String> terms) {
        addTerm(terms, data.summary().fullName());
        addTerm(terms, data.summary().displayName());
        collectTerms(data.profile(), terms, "email", "phone", "mobile", "address", "idNumber", "birthPlace", "nativePlace", "residence");
        collectTerms(data.careers(), terms, "organizationName");
        collectTerms(data.enterpriseRelations(), terms, "enterpriseName", "stockCode", "headquarters");
        collectTerms(data.familyMembers(), terms, "memberName", "protectedAlias");
        collectTerms(data.socialRelations(), terms, "organizationName");
        collectTerms(data.socialActivities(), terms, "activityName", "partnerName");
        collectTerms(data.publicReputations(), terms, "title", "publisherName");
        collectTerms(data.enterpriseMarketRelations(), terms, "counterpartName");
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

        private void add(Map<String, Object> target, Object raw) {
            switch (type) {
                case CODE -> putCode(target, name, raw);
                case NUMBER -> putNumberValue(target, name, raw);
                case DATE -> putDateValue(target, name, raw);
                case CURRENCY -> putCurrencyValue(target, name, raw);
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
    }

    private enum Type {
        CODE, NUMBER, DATE, CURRENCY
    }

    private static final class MaskingContext {
        private final Map<Long, String> referencesBySource = new LinkedHashMap<>();
        private final Map<String, Long> evidenceReferences = new LinkedHashMap<>();
        private final Set<String> prohibitedTerms = new LinkedHashSet<>();
        private final Map<String, String> enterpriseAliases = new LinkedHashMap<>();
        private final Map<String, String> familyAliases = new LinkedHashMap<>();
        private final Map<String, String> organizationAliases = new LinkedHashMap<>();
        private final Map<String, String> counterpartAliases = new LinkedHashMap<>();

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
