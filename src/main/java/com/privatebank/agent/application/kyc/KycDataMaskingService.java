package com.privatebank.agent.application.kyc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.agent.domain.kyc.KycCustomerData;
import com.privatebank.agent.domain.kyc.KycGraphRelationship;
import com.privatebank.agent.domain.kyc.KycInputValidationException;
import com.privatebank.agent.domain.kyc.KycMaskedInput;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.temporal.TemporalAccessor;
import java.util.Date;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
 * retained through an explicit contract; unknown fields are reported in dataCompleteness
 * and never cross the model boundary implicitly.
 */
@Service
public class KycDataMaskingService {

    private static final Pattern CONTROLLED_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,79}");
    private static final int MAX_RECORDS_PER_SECTION = 200;
    private static final int MAX_PAYLOAD_BYTES = 256 * 1024;
    private static final Pattern ORGANIZATION_IN_TEXT = Pattern.compile(
            "[\\p{IsHan}A-Za-z0-9·&（）()_-]{2,40}(?:股份有限公司|有限公司|公司|大学|学院|学校|银行|基金会|协会|委员会|研究院|实验室)");
    private static final Set<String> DIRECT_IDENTIFIER_KEYS = Set.of(
            "fullname", "displayname", "membername", "protectedalias", "enterprisename",
            "organizationname", "counterpartname", "partnername", "publishername",
            "stockcode", "email", "phone", "mobile", "idnumber", "accountnumber", "bankaccount",
            "birthdate", "nativeplace", "birthplace", "residence", "school", "schoolname", "rawtext");
    private static final Set<String> METADATA_KEYS = Set.of(
            "createdat", "updatedat", "createtime", "updatetime", "personid", "customerid", "sourceid");

    private final ObjectMapper objectMapper;
    private final KycSemanticProjectionService semanticProjectionService;
    private final KycInputSafetyValidator inputSafetyValidator;

    @Autowired
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
        registerSourceReferences(data, context);
        registerAliases(data, context);
        collectProhibitedTerms(data, context.prohibitedTerms);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractVersion", "kyc-input.v5");
        payload.put("person", person(data, context));
        payload.put("enterprise", enterprise(data, context));
        payload.put("family", family(data, context));
        payload.put("social", social(data, context));
        payload.put("relationshipGraph", relationshipGraph(data.graphRelationships(), context));
        if (supplement != null && !supplement.signals().isEmpty()) {
            Map<String, Object> managerSupplement = new LinkedHashMap<>();
            managerSupplement.put("signals", supplement.signals().stream().sorted().toList());
            payload.put("managerSupplement", managerSupplement);
        }
        payload.put("dataCompleteness", context.dataCompleteness());
        inputSafetyValidator.validate(payload, context.prohibitedTerms);

        byte[] serialized = serialize(payload);
        if (serialized.length > MAX_PAYLOAD_BYTES) {
            throw new KycInputValidationException("KYC 脱敏输入超过 256 KiB 上限");
        }

        return new KycMaskedInput(payload, context.evidenceReferences, context.prohibitedTerms, sha256(serialized));
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
        person.put("careers", careers(limit(data.careers(), "person.careers", context), context));
        person.put("riskPreferences", riskPreferences(
                limit(data.riskPreferences(), "person.riskPreferences", context), context));
        person.put("financialFacts", financialFacts(data.financialFacts(), context));
        person.put("holdings", holdings(data.holdings(), context));
        person.put("financialEvents", records(limit(data.financialEvents(), "person.financialEvents", context),
                context, "person.financialEvents",
                Field.code("eventType"), Field.date("eventDate"), Field.number("amount"),
                Field.currency("currencyCode"), Field.text("eventDescription"),
                Field.code("verificationStatus")));
        person.put("serviceRecords", deduplicate(records(limit(data.serviceRecords(), "person.serviceRecords", context),
                context, "person.serviceRecords",
                Field.code("serviceType"), Field.number("serviceYears"), Field.code("serviceFrequency"),
                Field.text("serviceDescription"), Field.code("verificationStatus"))));
        person.put("interactionSignals", interactionSignals(
                limit(data.interactionNotes(), "person.interactionSignals", context), context));
        return person;
    }

    private Map<String, Object> profile(Map<String, Object> source, MaskingContext context) {
        Map<String, Object> profile = recordBase(source, context, "person.profile");
        putNumber(profile, "birthYear", value(source, "birthYear"));
        putMappedCode(profile, "gender", value(source, "gender"), "person.profile.gender", context);
        putMappedCode(profile, "maritalStatus", value(source, "maritalStatus"),
                "person.profile.maritalStatus", context);
        putMappedCode(profile, "educationLevel", value(source, "educationLevel"),
                "person.profile.educationLevel", context);
        putMappedCode(profile, "healthStatus", value(source, "healthSummary"),
                "person.profile.healthStatus", context);
        putCoarseLocation(profile, "nativePlace", value(source, "nativePlace"));
        putCoarseLocation(profile, "birthPlace", value(source, "birthPlace"));
        putCoarseLocation(profile, "residence", value(source, "residence"));
        putControlled(profile, "verificationStatus", value(source, "verificationStatus"));
        return profile;
    }

    private List<Map<String, Object>> careers(List<Map<String, Object>> source, MaskingContext context) {
        return source == null ? List.of() : source.stream().map(record -> {
            Map<String, Object> masked = recordBase(record, context, "person.careers");
            putAlias(masked, "personAlias", "P-1");
            putAlias(masked, "organizationAlias", context.organizationAlias(record));
            putControlled(masked, "roleCategory", semanticProjectionService.roleCategory(
                    value(record, "positionTitle"), value(record, "organizationName")));
            putDate(masked, "startDate", value(record, "startDate"));
            putDate(masked, "endDate", value(record, "endDate"));
            putSanitizedText(masked, "positionTitle", value(record, "positionTitle"),
                    context, "person.careers.positionTitle", 300);
            putSanitizedText(masked, "careerDescription", value(record, "careerDescription"),
                    context, "person.careers.careerDescription", 600);
            putControlled(masked, "verificationStatus", value(record, "verificationStatus"));
            return masked;
        }).toList();
    }

    private List<Map<String, Object>> riskPreferences(List<Map<String, Object>> source, MaskingContext context) {
        return source == null ? List.of() : source.stream().map(record -> {
            Map<String, Object> masked = recordBase(record, context, "person.riskPreferences");
            putControlled(masked, "riskLevel", value(record, "riskLevel"));
            putNumber(masked, "maxDrawdown", value(record, "maxDrawdown"));
            putMappedCode(masked, "investmentHorizon", value(record, "investmentHorizon"),
                    "person.riskPreferences.investmentHorizon", context);
            putMappedCode(masked, "liquidityRequirement", value(record, "liquidityRequirement"),
                    "person.riskPreferences.liquidityRequirement", context);
            putMappedCode(masked, "actualPreference", value(record, "actualPreference"),
                    "person.riskPreferences.actualPreference", context);
            putSanitizedText(masked, "preferenceDescription", value(record, "preferenceDescription"),
                    context, "person.riskPreferences.preferenceDescription", 600);
            putControlled(masked, "verificationStatus", value(record, "verificationStatus"));
            return masked;
        }).toList();
    }

    private List<Map<String, Object>> financialFacts(List<Map<String, Object>> source, MaskingContext context) {
        List<Map<String, Object>> limited = limit(source, "person.financialFacts", context);
        List<Map<String, Object>> projected = limited.stream().map(record -> {
            Map<String, Object> masked = recordBase(record, context, "person.financialFacts");
            putControlled(masked, "factCategory", value(record, "factCategory"));
            putControlled(masked, "assetCategory", semanticProjectionService.assetCategory(value(record, "assetType")));
            putNumber(masked, "amount", value(record, "amount"));
            putCurrency(masked, "currencyCode", value(record, "currencyCode"));
            putNumber(masked, "percentage", value(record, "percentage"));
            putBoolean(masked, "estimateFlag", value(record, "estimateFlag"));
            putDate(masked, "effectiveDate", value(record, "effectiveDate"));
            putSanitizedText(masked, "description", value(record, "description"),
                    context, "person.financialFacts.description", 600);
            putControlled(masked, "verificationStatus", value(record, "verificationStatus"));
            return masked;
        }).toList();
        return deduplicate(projected);
    }

    private List<Map<String, Object>> holdings(List<Map<String, Object>> source, MaskingContext context) {
        return limit(source, "person.holdings", context).stream().map(record -> {
            Map<String, Object> masked = recordBase(record, context, "person.holdings");
            putControlled(masked, "productCategory", semanticProjectionService.assetCategory(value(record, "productType")));
            putNumber(masked, "amount", value(record, "amount"));
            putCurrency(masked, "currencyCode", value(record, "currencyCode"));
            putDate(masked, "maturityDate", value(record, "maturityDate"));
            putControlled(masked, "riskLevel", value(record, "riskLevel"));
            putSanitizedText(masked, "holdingDescription", value(record, "holdingDescription"),
                    context, "person.holdings.holdingDescription", 600);
            putControlled(masked, "verificationStatus", value(record, "verificationStatus"));
            return masked;
        }).toList();
    }

    private List<Map<String, Object>> interactionSignals(List<Map<String, Object>> source, MaskingContext context) {
        return source == null ? List.of() : source.stream().map(record -> {
            Map<String, Object> masked = recordBase(record, context, "person.interactionSignals");
            putAlias(masked, "personAlias", "P-1");
            putControlled(masked, "noteType", value(record, "noteType"));
            putBoolean(masked, "isExplicitExpression", value(record, "isExplicitExpression"));
            putCodes(masked, "topicCodes", semanticProjectionService.interactionTopics(value(record, "noteText")));
            putSanitizedText(masked, "noteText", value(record, "noteText"),
                    context, "person.interactionSignals.noteText", 600);
            putControlled(masked, "verificationStatus", value(record, "verificationStatus"));
            return masked;
        }).toList();
    }

    private Map<String, Object> enterprise(KycCustomerData data, MaskingContext context) {
        Map<String, Object> enterprise = new LinkedHashMap<>();
        enterprise.put("relations", enterpriseRelations(
                limit(data.enterpriseRelations(), "enterprise.relations", context), context));
        enterprise.put("businesses", enterpriseBusinesses(
                limit(data.enterpriseBusinesses(), "enterprise.businesses", context), context));
        enterprise.put("financialMetrics", enterpriseFinancialMetrics(
                limit(data.enterpriseFinancialMetrics(), "enterprise.financialMetrics", context), context));
        enterprise.put("events", enterpriseEvents(
                limit(data.enterpriseEvents(), "enterprise.events", context), context));
        enterprise.put("marketRelations", enterpriseMarketRelations(
                limit(data.enterpriseMarketRelations(), "enterprise.marketRelations", context), context));
        return enterprise;
    }

    private List<Map<String, Object>> enterpriseRelations(List<Map<String, Object>> source, MaskingContext context) {
        return source == null ? List.of() : source.stream().map(record -> {
            Map<String, Object> masked = recordBase(record, context, "enterprise.relations");
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
            putCoarseLocation(masked, "headquarters", value(record, "headquarters"));
            putControlled(masked, "verificationStatus", value(record, "verificationStatus"));
            return masked;
        }).toList();
    }

    private List<Map<String, Object>> enterpriseBusinesses(List<Map<String, Object>> source, MaskingContext context) {
        if (source == null) {
            return List.of();
        }
        List<Map<String, Object>> projected = source.stream().map(record -> {
            Map<String, Object> masked = recordBase(record, context, "enterprise.businesses");
            putAlias(masked, "enterpriseAlias", context.enterpriseAlias(record));
            putCodes(masked, "businessCategories", semanticProjectionService.businessCategories(
                    value(record, "businessLine"), value(record, "businessDescription")));
            Object description = value(record, "businessDescription") != null
                    ? value(record, "businessDescription") : value(record, "businessLine");
            putSanitizedText(masked, "businessDescription", description,
                    context, "enterprise.businesses.businessDescription", 600);
            putControlled(masked, "verificationStatus", value(record, "verificationStatus"));
            return masked;
        }).toList();
        return deduplicate(projected);
    }

    private List<Map<String, Object>> enterpriseFinancialMetrics(
            List<Map<String, Object>> source, MaskingContext context) {
        if (source == null) {
            return List.of();
        }
        List<Map<String, Object>> projected = source.stream().map(record -> {
            Map<String, Object> masked = recordBase(record, context, "enterprise.financialMetrics");
            putAlias(masked, "enterpriseAlias", context.enterpriseAlias(record));
            putPeriod(masked, "reportingPeriod", value(record, "reportingPeriod"), context,
                    "enterprise.financialMetrics.reportingPeriod");
            putMappedCode(masked, "metricName", value(record, "metricName"),
                    "enterprise.financialMetrics.metricName", context);
            putNumber(masked, "metricValue", value(record, "metricValue"));
            putUnit(masked, "unitName", value(record, "unitName"), context,
                    "enterprise.financialMetrics.unitName");
            putControlled(masked, "verificationStatus", value(record, "verificationStatus"));
            return masked;
        }).toList();
        return deduplicate(projected);
    }

    private List<Map<String, Object>> enterpriseEvents(List<Map<String, Object>> source, MaskingContext context) {
        if (source == null) {
            return List.of();
        }
        List<Map<String, Object>> projected = source.stream().map(record -> {
            Map<String, Object> masked = recordBase(record, context, "enterprise.events");
            putAlias(masked, "enterpriseAlias", context.enterpriseAlias(record));
            putControlled(masked, "eventType", value(record, "eventType"));
            putDate(masked, "eventDate", value(record, "eventDate"));
            putControlled(masked, "riskLevel", value(record, "riskLevel"));
            putCodes(masked, "eventSignals", semanticProjectionService.enterpriseEventSignals(value(record, "eventDescription")));
            putSanitizedText(masked, "eventDescription", value(record, "eventDescription"),
                    context, "enterprise.events.eventDescription", 600);
            putControlled(masked, "verificationStatus", value(record, "verificationStatus"));
            return masked;
        }).toList();
        return deduplicate(projected);
    }

    private List<Map<String, Object>> enterpriseMarketRelations(List<Map<String, Object>> source, MaskingContext context) {
        if (source == null) {
            return List.of();
        }
        List<Map<String, Object>> projected = source.stream().map(record -> {
            Map<String, Object> masked = recordBase(record, context, "enterprise.marketRelations");
            putAlias(masked, "enterpriseAlias", context.enterpriseAlias(record));
            putAlias(masked, "counterpartyAlias", context.counterpartyAlias(record));
            putControlled(masked, "relationType", value(record, "relationType"));
            putControlled(masked, "verificationStatus", value(record, "verificationStatus"));
            return masked;
        }).toList();
        return deduplicate(projected);
    }

    private Map<String, Object> family(KycCustomerData data, MaskingContext context) {
        Map<String, Object> family = new LinkedHashMap<>();
        family.put("members", familyMembers(limit(data.familyMembers(), "family.members", context), context));
        family.put("relations", familyRelations(limit(data.familyRelations(), "family.relations", context), context));
        family.put("successionArrangements", successionArrangements(
                limit(data.successionArrangements(), "family.successionArrangements", context), context));
        return family;
    }

    private List<Map<String, Object>> familyMembers(List<Map<String, Object>> source, MaskingContext context) {
        return source == null ? List.of() : source.stream().map(record -> {
            Map<String, Object> masked = recordBase(record, context, "family.members");
            putAlias(masked, "familyAlias", context.familyAlias(record));
            putControlled(masked, "publicDisclosureLevel", value(record, "publicDisclosureLevel"));
            putSanitizedText(masked, "memberDescription", value(record, "memberDescription"),
                    context, "family.members.memberDescription", 600);
            putControlled(masked, "verificationStatus", value(record, "verificationStatus"));
            return masked;
        }).toList();
    }

    private List<Map<String, Object>> familyRelations(List<Map<String, Object>> source, MaskingContext context) {
        return source == null ? List.of() : source.stream().map(record -> {
            Map<String, Object> masked = recordBase(record, context, "family.relations");
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
            Map<String, Object> masked = recordBase(record, context, "family.successionArrangements");
            putAlias(masked, "personAlias", "P-1");
            putAlias(masked, "enterpriseAlias", context.enterpriseAlias(record));
            putControlled(masked, "arrangementStatus", value(record, "arrangementStatus"));
            putControlled(masked, "governanceCategory", semanticProjectionService.governanceCategory(
                    value(record, "governanceModel"), value(record, "arrangementDescription")));
            putSanitizedText(masked, "arrangementDescription", value(record, "arrangementDescription"),
                    context, "family.successionArrangements.arrangementDescription", 600);
            putControlled(masked, "verificationStatus", value(record, "verificationStatus"));
            return masked;
        }).toList();
    }

    private Map<String, Object> social(KycCustomerData data, MaskingContext context) {
        Map<String, Object> social = new LinkedHashMap<>();
        social.put("relations", socialRelations(limit(data.socialRelations(), "social.relations", context), context));
        social.put("activities", socialActivities(limit(data.socialActivities(), "social.activities", context), context));
        social.put("publicReputation", publicReputation(
                limit(data.publicReputations(), "social.publicReputation", context), context));
        social.put("reputationRisks", reputationRisks(
                limit(data.reputationRisks(), "social.reputationRisks", context), context));
        return social;
    }

    private List<Map<String, Object>> socialRelations(List<Map<String, Object>> source, MaskingContext context) {
        if (source == null) {
            return List.of();
        }
        List<Map<String, Object>> projected = source.stream().map(record -> {
            Map<String, Object> masked = recordBase(record, context, "social.relations");
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
        return deduplicate(projected);
    }

    private List<Map<String, Object>> socialActivities(List<Map<String, Object>> source, MaskingContext context) {
        if (source == null) {
            return List.of();
        }
        List<Map<String, Object>> projected = source.stream().map(record -> {
            Map<String, Object> masked = recordBase(record, context, "social.activities");
            putAlias(masked, "personAlias", "P-1");
            putControlled(masked, "activityType", value(record, "activityType"));
            putDate(masked, "activityDate", value(record, "activityDate"));
            putNumber(masked, "amount", value(record, "amount"));
            putCurrency(masked, "currencyCode", value(record, "currencyCode"));
            putCodes(masked, "activitySignals", semanticProjectionService.activitySignals(value(record, "activityDescription")));
            putSanitizedText(masked, "activityName", value(record, "activityName"),
                    context, "social.activities.activityName", 600);
            putControlled(masked, "verificationStatus", value(record, "verificationStatus"));
            return masked;
        }).toList();
        return deduplicate(projected);
    }

    private List<Map<String, Object>> publicReputation(List<Map<String, Object>> source, MaskingContext context) {
        if (source == null) {
            return List.of();
        }
        List<Map<String, Object>> projected = source.stream().map(record -> {
            Map<String, Object> masked = recordBase(record, context, "social.publicReputation");
            putAlias(masked, "personAlias", "P-1");
            putControlled(masked, "reputationType", value(record, "reputationType"));
            putDate(masked, "publicationDate", value(record, "publicationDate"));
            putCodes(masked, "reputationSignals", semanticProjectionService.reputationSignals(
                    value(record, "title"), value(record, "description")));
            putSanitizedText(masked, "title", value(record, "title"),
                    context, "social.publicReputation.title", 600);
            putControlled(masked, "verificationStatus", value(record, "verificationStatus"));
            return masked;
        }).toList();
        return deduplicate(projected);
    }

    private List<Map<String, Object>> reputationRisks(List<Map<String, Object>> source, MaskingContext context) {
        if (source == null) {
            return List.of();
        }
        List<Map<String, Object>> projected = source.stream().map(record -> {
            Map<String, Object> masked = recordBase(record, context, "social.reputationRisks");
            putAlias(masked, "personAlias", "P-1");
            putControlled(masked, "riskLevel", value(record, "riskLevel"));
            putDate(masked, "eventDate", value(record, "eventDate"));
            putCodes(masked, "riskCategories", semanticProjectionService.reputationRiskCategories(
                    value(record, "riskTopic"), value(record, "riskDescription")));
            putSanitizedText(masked, "riskTopic", value(record, "riskTopic"),
                    context, "social.reputationRisks.riskTopic", 600);
            putControlled(masked, "verificationStatus", value(record, "verificationStatus"));
            return masked;
        }).toList();
        return deduplicate(projected);
    }

    private Map<String, Object> relationshipGraph(
            List<KycGraphRelationship> relationships, MaskingContext context) {
        if (relationships == null || relationships.isEmpty()) {
            Map<String, Object> graph = new LinkedHashMap<>();
            graph.put("available", false);
            graph.put("relationshipCount", 0);
            graph.put("evidenceRefs", List.of());
            graph.put("relationships", List.of());
            return graph;
        }
        List<KycGraphRelationship> limited = relationships.size() > MAX_RECORDS_PER_SECTION
                ? relationships.subList(0, MAX_RECORDS_PER_SECTION) : relationships;
        if (relationships.size() > MAX_RECORDS_PER_SECTION) {
            context.truncated("relationshipGraph.relationships");
        }
        List<Map<String, Object>> projected = limited.stream().map(relationship -> {
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
            List<Map<String, Object>> source, MaskingContext context, String path, Field... fields) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        return source.stream().map(record -> record(record, context, path, fields)).toList();
    }

    private Map<String, Object> record(
            Map<String, Object> source, MaskingContext context, String path, Field... fields) {
        Map<String, Object> masked = recordBase(source, context, path);
        for (Field field : fields) {
            field.add(masked, value(source, field.keys()), context, path + "." + field.name());
        }
        return masked;
    }

    private Map<String, Object> recordBase(Map<String, Object> source, MaskingContext context, String path) {
        Map<String, Object> masked = new LinkedHashMap<>();
        Long sourceId = longValue(value(source, "sourceId"));
        if (sourceId != null) {
            masked.put("sourceRef", context.sourceReference(sourceId));
        }
        context.observeSourceFields(source, path);
        return masked;
    }

    private String coarseLocation(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = KycSensitiveTextPolicy.normalizeText(raw).trim();
        java.util.regex.Matcher province = Pattern.compile(
                "([\\p{IsHan}]{2,12}(?:省|自治区|特别行政区))").matcher(normalized);
        if (province.find()) {
            String provinceName = province.group(1);
            String remainder = normalized.substring(province.end());
            java.util.regex.Matcher city = Pattern.compile("([\\p{IsHan}]{2,8}市)").matcher(remainder);
            return city.find() ? provinceName + "/" + city.group(1) : provinceName;
        }
        java.util.regex.Matcher city = Pattern.compile("([\\p{IsHan}]{2,8}市)").matcher(normalized);
        if (city.find()) {
            return city.group(1);
        }
        return switch (normalized) {
            case "北京", "北京市" -> "北京市";
            case "上海", "上海市" -> "上海市";
            case "天津", "天津市" -> "天津市";
            case "重庆", "重庆市" -> "重庆市";
            case "深圳", "深圳市" -> "深圳市";
            case "广州", "广州市" -> "广州市";
            case "杭州", "杭州市" -> "杭州市";
            case "河北" -> "河北省";
            case "山西" -> "山西省";
            case "辽宁" -> "辽宁省";
            case "吉林" -> "吉林省";
            case "黑龙江" -> "黑龙江省";
            case "江苏" -> "江苏省";
            case "浙江" -> "浙江省";
            case "安徽" -> "安徽省";
            case "福建" -> "福建省";
            case "江西" -> "江西省";
            case "山东" -> "山东省";
            case "河南" -> "河南省";
            case "湖北" -> "湖北省";
            case "湖南" -> "湖南省";
            case "广东" -> "广东省";
            case "海南" -> "海南省";
            case "四川" -> "四川省";
            case "贵州" -> "贵州省";
            case "云南" -> "云南省";
            case "陕西" -> "陕西省";
            case "甘肃" -> "甘肃省";
            case "青海" -> "青海省";
            case "台湾" -> "台湾省";
            case "内蒙古" -> "内蒙古自治区";
            case "广西" -> "广西壮族自治区";
            case "西藏" -> "西藏自治区";
            case "宁夏" -> "宁夏回族自治区";
            case "新疆" -> "新疆维吾尔自治区";
            case "香港", "香港特别行政区" -> "香港特别行政区";
            case "澳门", "澳门特别行政区" -> "澳门特别行政区";
            default -> "LOCATION_REDACTED";
        };
    }

    private void collectProhibitedTerms(KycCustomerData data, Set<String> terms) {
        addTerm(terms, data.summary().fullName());
        addTerm(terms, data.summary().displayName());
        collectTerms(data.profile(), terms,
                "email", "phone", "mobile", "address", "idNumber", "birthDate", "schoolName");
        addLocationTerm(terms, value(data.profile(), "nativePlace"));
        addLocationTerm(terms, value(data.profile(), "birthPlace"));
        addLocationTerm(terms, value(data.profile(), "residence"));
        collectTerms(data.careers(), terms, "organizationName");
        collectTerms(data.enterpriseRelations(), terms, "enterpriseName", "stockCode");
        if (data.enterpriseRelations() != null) {
            data.enterpriseRelations().forEach(record -> addLocationTerm(terms, value(record, "headquarters")));
        }
        collectTerms(data.familyMembers(), terms, "memberName", "protectedAlias");
        collectTerms(data.socialRelations(), terms, "organizationName");
        collectTerms(data.socialActivities(), terms, "partnerName");
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
        List<Map<String, Object>> stableRecords = records.stream()
                .sorted(Comparator.comparing(record -> new java.util.TreeMap<>(record).toString()))
                .toList();
        for (Map<String, Object> record : stableRecords) {
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

    private void addLocationTerm(Set<String> terms, Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            return;
        }
        String normalized = KycSensitiveTextPolicy.normalizeText(text).trim();
        String coarse = coarseLocation(normalized);
        String normalizedCoarse = coarse == null ? "" : coarse.replace("/", "");
        String comparableCoarse = normalizedCoarse.replaceAll("(?:特别行政区|自治区|省|市)$", "");
        String comparableRaw = normalized.replaceAll("(?:特别行政区|自治区|省|市)$", "");
        if (!"LOCATION_REDACTED".equals(coarse)
                && (normalized.equals(coarse) || comparableCoarse.endsWith(comparableRaw))) {
            return;
        }
        addTerm(terms, normalized);
    }

    private void registerSourceReferences(KycCustomerData data, MaskingContext context) {
        List<Long> sourceIds = new ArrayList<>();
        collectSourceIds(data.profile(), sourceIds);
        List<List<Map<String, Object>>> recordGroups = new ArrayList<>();
        Collections.addAll(recordGroups,
                data.careers(), data.riskPreferences(), data.financialFacts(), data.holdings(),
                data.financialEvents(), data.serviceRecords(), data.interactionNotes(),
                data.enterpriseRelations(), data.enterpriseBusinesses(), data.enterpriseFinancialMetrics(),
                data.enterpriseEvents(), data.enterpriseMarketRelations(), data.familyMembers(),
                data.familyRelations(), data.successionArrangements(), data.socialRelations(),
                data.socialActivities(), data.publicReputations(), data.reputationRisks());
        for (List<Map<String, Object>> records : recordGroups) {
            if (records != null) {
                records.forEach(record -> collectSourceIds(record, sourceIds));
            }
        }
        if (data.graphRelationships() != null) {
            data.graphRelationships().stream().map(KycGraphRelationship::sourceId)
                    .filter(java.util.Objects::nonNull).forEach(sourceIds::add);
        }
        sourceIds.stream().distinct().sorted().forEach(context::sourceReference);
    }

    private void collectSourceIds(Map<String, Object> record, List<Long> target) {
        Long sourceId = longValue(value(record, "sourceId"));
        if (sourceId != null) {
            target.add(sourceId);
        }
    }

    private List<Map<String, Object>> limit(
            List<Map<String, Object>> source, String path, MaskingContext context) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        if (source.size() > MAX_RECORDS_PER_SECTION) {
            context.truncated(path);
            return List.copyOf(source.subList(0, MAX_RECORDS_PER_SECTION));
        }
        return source;
    }

    private List<Map<String, Object>> deduplicate(List<Map<String, Object>> source) {
        Map<String, Map<String, Object>> distinct = new LinkedHashMap<>();
        for (Map<String, Object> record : source) {
            Map<String, Object> businessContent = new LinkedHashMap<>(record);
            businessContent.remove("sourceRef");
            String key;
            try {
                key = objectMapper.writeValueAsString(businessContent);
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("无法生成 KYC 记录去重键", exception);
            }
            distinct.putIfAbsent(key, record);
        }
        return List.copyOf(distinct.values());
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
        if (raw instanceof Date date) {
            target.put(key, date.toInstant());
        } else if (raw instanceof TemporalAccessor) {
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

    private void putSanitizedText(
            Map<String, Object> target, String key, Object raw, MaskingContext context, String path, int maxLength) {
        if (!(raw instanceof String text) || text.isBlank()) {
            return;
        }
        String sanitized = sanitizeText(text, context).trim();
        if (sanitized.isEmpty()) {
            return;
        }
        int length = sanitized.codePointCount(0, sanitized.length());
        if (length > maxLength) {
            context.omit(path, "TEXT_TRUNCATED");
            sanitized = KycSensitiveTextPolicy.truncateCodePoints(sanitized, maxLength);
        }
        target.put(key, sanitized);
    }

    private static String sanitizeText(String raw, MaskingContext context) {
        String sanitized = context.redact(raw);
        sanitized = KycSensitiveTextPolicy.redactDirectIdentifiers(sanitized);
        return ORGANIZATION_IN_TEXT.matcher(sanitized).replaceAll("O-REDACTED");
    }

    private void putCoarseLocation(Map<String, Object> target, String key, Object raw) {
        if (raw instanceof String text && !text.isBlank()) {
            target.put(key, coarseLocation(text));
        }
    }

    private void putMappedCode(
            Map<String, Object> target, String key, Object raw, String path, MaskingContext context) {
        if (!(raw instanceof String text) || text.isBlank()) {
            return;
        }
        String code = mappedCode(key, text);
        if (code == null) {
            context.omit(path, "UNKNOWN_ENUM");
        } else {
            target.put(key, code);
        }
    }

    private String mappedCode(String field, String raw) {
        String text = KycSensitiveTextPolicy.normalizeText(raw).trim();
        String upper = text.toUpperCase(Locale.ROOT);
        if (CONTROLLED_CODE.matcher(upper).matches()) {
            return upper;
        }
        return switch (field) {
            case "gender" -> containsAny(text, "男", "男性") ? "MALE"
                    : containsAny(text, "女", "女性") ? "FEMALE" : null;
            case "maritalStatus" -> containsAny(text, "未婚") ? "UNMARRIED"
                    : containsAny(text, "已婚", "婚姻") ? "MARRIED"
                    : containsAny(text, "离异", "离婚") ? "DIVORCED"
                    : containsAny(text, "丧偶") ? "WIDOWED" : null;
            case "educationLevel" -> containsAny(text, "博士") ? "DOCTORATE"
                    : containsAny(text, "硕士", "研究生") ? "MASTER"
                    : containsAny(text, "本科", "学士") ? "BACHELOR"
                    : containsAny(text, "大专", "专科") ? "COLLEGE"
                    : containsAny(text, "高中", "中专") ? "HIGH_SCHOOL" : null;
            case "healthStatus" -> containsAny(text, "良好", "健康", "正常") ? "GOOD"
                    : containsAny(text, "一般", "稳定") ? "STABLE"
                    : containsAny(text, "疾病", "欠佳", "异常", "治疗") ? "HEALTH_CONCERN" : null;
            case "investmentHorizon" -> containsAny(text, "长期", "5年以上", "五年以上") ? "LONG_TERM"
                    : containsAny(text, "中期", "3-5", "三至五") ? "MEDIUM_TERM"
                    : containsAny(text, "短期", "1年", "一年") ? "SHORT_TERM" : null;
            case "liquidityRequirement" -> containsAny(text, "不高", "较低", "低流动") ? "LOW"
                    : containsAny(text, "很高", "较高", "高流动", "随时") ? "HIGH"
                    : containsAny(text, "中等", "适中", "一般") ? "MEDIUM" : null;
            case "actualPreference" -> containsAny(text, "股票", "权益", "股权") ? "EQUITY"
                    : containsAny(text, "债券", "固收") ? "FIXED_INCOME"
                    : containsAny(text, "私募", "另类") ? "PRIVATE_MARKET"
                    : containsAny(text, "现金", "存款") ? "CASH"
                    : containsAny(text, "均衡", "多元", "组合") ? "BALANCED" : null;
            case "metricName" -> containsAny(text, "总营收", "营收", "营业收入") ? "TOTAL_REVENUE"
                    : containsAny(text, "净利润", "归母净利润") ? "NET_PROFIT"
                    : containsAny(text, "毛利率") ? "GROSS_MARGIN"
                    : containsAny(text, "净利率") ? "NET_MARGIN"
                    : containsAny(text, "总资产") ? "TOTAL_ASSETS"
                    : containsAny(text, "总负债") ? "TOTAL_LIABILITIES"
                    : containsAny(text, "经营现金流") ? "OPERATING_CASH_FLOW" : null;
            default -> null;
        };
    }

    private boolean containsAny(String source, String... values) {
        for (String value : values) {
            if (source.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private void putPeriod(
            Map<String, Object> target, String key, Object raw, MaskingContext context, String path) {
        if (raw instanceof String text && text.matches("[0-9]{4}(?:Q[1-4]|-(?:H[12]|Q[1-4]))?")) {
            target.put(key, text);
        } else if (raw != null) {
            context.omit(path, "INVALID_PERIOD");
        }
    }

    private void putUnit(
            Map<String, Object> target, String key, Object raw, MaskingContext context, String path) {
        if (raw instanceof String text && Set.of(
                "CNY", "USD", "HKD", "EUR", "CNY_10K", "CNY_1M", "CNY_100M", "PERCENT", "COUNT")
                .contains(text.toUpperCase(Locale.ROOT))) {
            target.put(key, text.toUpperCase(Locale.ROOT));
        } else if (raw != null) {
            context.omit(path, "UNKNOWN_UNIT");
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

    private byte[] serialize(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化 KYC 脱敏输入", exception);
        }
    }

    private String sha256(byte[] payload) {
        try {
            return toHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException exception) {
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

        private void add(
                Map<String, Object> target, Object raw, MaskingContext context, String path) {
            switch (type) {
                case CODE -> putCode(target, name, raw);
                case NUMBER -> putNumberValue(target, name, raw);
                case DATE -> putDateValue(target, name, raw);
                case CURRENCY -> putCurrencyValue(target, name, raw);
                case TEXT -> putTextValue(target, name, raw, context, path);
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
            if (raw instanceof Date date) {
                target.put(key, date.toInstant());
            } else if (raw instanceof TemporalAccessor) {
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

        private static void putTextValue(
                Map<String, Object> target, String key, Object raw, MaskingContext context, String path) {
            if (raw instanceof String text && !text.isBlank()) {
                String sanitized = sanitizeText(text, context).trim();
                if (sanitized.codePointCount(0, sanitized.length()) > 600) {
                    context.omit(path, "TEXT_TRUNCATED");
                    sanitized = KycSensitiveTextPolicy.truncateCodePoints(sanitized, 600);
                }
                if (!sanitized.isEmpty()) {
                    target.put(key, sanitized);
                }
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
        private final Map<String, String> aliasesByNormalizedName = new LinkedHashMap<>();
        private final Set<String> ambiguousNormalizedNames = new LinkedHashSet<>();
        private final Set<String> truncatedSections = new LinkedHashSet<>();
        private final Map<String, String> omissions = new LinkedHashMap<>();
        private int suppressedOmissionCount;
        private List<Map.Entry<String, String>> sortedRedactions;
        private List<String> sortedSensitiveTerms;

        private static final Set<String> KNOWN_SOURCE_FIELDS = Set.of(
                "gender", "birthyear", "maritalstatus", "educationlevel", "healthsummary",
                "positiontitle", "careerdescription", "startdate", "enddate",
                "risklevel", "maxdrawdown", "investmenthorizon", "liquidityrequirement",
                "actualpreference", "preferencedescription", "factcategory", "assettype", "amount",
                "currencycode", "percentage", "estimateflag", "description", "effectivedate",
                "producttype", "maturitydate", "holdingdescription", "eventtype", "eventdate",
                "eventdescription", "servicetype", "serviceyears", "servicefrequency", "servicedescription",
                "notetype", "notetext", "isexplicitexpression", "relationtype", "title",
                "ownershippercentage", "votingrightpercentage", "iscorerelation", "validfrom", "validto",
                "industryname", "headquarters", "employeecount", "businessline", "businessdescription",
                "reportingperiod", "metricname", "metricvalue", "unitname", "counterpartname",
                "relationdescription", "publicdisclosurelevel", "memberdescription", "arrangementstatus",
                "governancemodel", "arrangementdescription", "organizationtype", "roletitle",
                "activitytype", "activityname", "activitydescription", "reputationtype", "publishername",
                "risktopic", "riskdescription", "sourcelevel", "verificationstatus");
        private static final Set<String> GENERIC_ORGANIZATION_SHORT_NAMES = Set.of(
                "中国", "中华", "国家", "国际", "全球", "亚洲", "北京", "上海", "天津", "重庆",
                "广东", "江苏", "浙江", "山东", "四川", "深圳", "广州", "杭州");

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
            String result = alias(enterpriseAliases, "E", entityKey(record, "enterpriseId", "enterpriseName"));
            bindName(lookup(record, "enterpriseName"), result);
            return result;
        }

        private String familyAlias(Map<String, Object> record) {
            return alias(familyAliases, "F", entityKey(record, "familyMemberId", "memberName", "protectedAlias"));
        }

        private String organizationAlias(Map<String, Object> record) {
            String known = aliasByName(lookup(record, "organizationName"));
            if (known != null) {
                return known;
            }
            String result = alias(organizationAliases, "O",
                    entityKey(record, "socialOrganizationId", "organizationId", "organizationName"));
            bindName(lookup(record, "organizationName"), result);
            return result;
        }

        private String counterpartyAlias(Map<String, Object> record) {
            String known = aliasByName(lookup(record, "counterpartName"));
            if (known != null) {
                return known;
            }
            String result = alias(counterpartAliases, "C", entityKey(record, "counterpartId", "counterpartName"));
            bindName(lookup(record, "counterpartName"), result);
            return result;
        }

        private String activityPartyAlias(Map<String, Object> record) {
            String known = aliasByName(lookup(record, "partnerName"));
            if (known != null) {
                return known;
            }
            String result = alias(organizationAliases, "O", entityKey(record, "partnerName"));
            bindName(lookup(record, "partnerName"), result);
            return result;
        }

        private String publisherAlias(Map<String, Object> record) {
            String known = aliasByName(lookup(record, "publisherName"));
            if (known != null) {
                return known;
            }
            String result = alias(organizationAliases, "O", entityKey(record, "publisherName"));
            bindName(lookup(record, "publisherName"), result);
            return result;
        }

        private void registerEntity(String raw, String alias, boolean organization) {
            if (raw == null || raw.isBlank() || alias == null) {
                return;
            }
            addRedaction(raw.trim(), alias);
            addProhibited(raw.trim());
            bindName(raw.trim(), alias);
            String withoutParentheses = raw.replaceAll("[（(].*?[）)]", "").trim();
            addRedaction(withoutParentheses, alias);
            addProhibited(withoutParentheses);
            bindName(withoutParentheses, alias);
            if (organization) {
                String shortName = withoutParentheses
                        .replaceAll("(?:股份)?有限公司$", "")
                        .trim();
                addRedaction(shortName, alias);
                addProhibited(shortName);
                bindName(shortName, alias);
                String commonName = shortName
                        .replaceAll("(?:控股|集团|企业|科技|实业|工业|股份)+$", "")
                        .trim();
                if (!GENERIC_ORGANIZATION_SHORT_NAMES.contains(commonName)) {
                    addRedaction(commonName, alias);
                    addProhibited(commonName);
                    bindName(commonName, alias);
                }
            }
        }

        private void addRedaction(String raw, String alias) {
            if (raw != null && raw.length() >= 2) {
                redactions.putIfAbsent(raw, alias);
                sortedRedactions = null;
            }
        }

        private void addProhibited(String raw) {
            if (raw != null && raw.length() >= 2 && raw.length() <= 255) {
                prohibitedTerms.add(raw);
                sortedSensitiveTerms = null;
            }
        }

        private String redact(String source) {
            String sanitized = source;
            if (sortedRedactions == null) {
                sortedRedactions = redactions.entrySet().stream()
                        .sorted((left, right) -> Integer.compare(right.getKey().length(), left.getKey().length()))
                        .toList();
            }
            for (Map.Entry<String, String> entry : sortedRedactions) {
                sanitized = KycSensitiveTextPolicy.replaceTerm(
                        sanitized, entry.getKey(), entry.getValue());
            }
            if (sortedSensitiveTerms == null) {
                sortedSensitiveTerms = prohibitedTerms.stream()
                        .filter(term -> term != null && term.trim().length() >= 2)
                        .map(String::trim)
                        .sorted((left, right) -> Integer.compare(right.length(), left.length()))
                        .toList();
            }
            for (String term : sortedSensitiveTerms) {
                sanitized = KycSensitiveTextPolicy.replaceTerm(
                        sanitized, term, "[SENSITIVE_REDACTED]");
            }
            return sanitized;
        }

        private void bindName(Object raw, String alias) {
            if (!(raw instanceof String text) || text.isBlank() || alias == null) {
                return;
            }
            String normalized = normalizedEntityName(text);
            if (normalized.isEmpty() || ambiguousNormalizedNames.contains(normalized)) {
                return;
            }
            String existing = aliasesByNormalizedName.putIfAbsent(normalized, alias);
            if (existing != null && !existing.equals(alias)) {
                aliasesByNormalizedName.remove(normalized);
                ambiguousNormalizedNames.add(normalized);
            }
        }

        private String aliasByName(Object raw) {
            if (!(raw instanceof String text) || text.isBlank()) {
                return null;
            }
            String normalized = normalizedEntityName(text);
            return ambiguousNormalizedNames.contains(normalized) ? null : aliasesByNormalizedName.get(normalized);
        }

        private String normalizedEntityName(String raw) {
            return KycSensitiveTextPolicy.normalizeText(raw)
                    .replaceAll("[（(].*?[）)]", "")
                    .replaceAll("\\s+", "")
                    .toLowerCase(Locale.ROOT);
        }

        private void truncated(String path) {
            truncatedSections.add(path);
        }

        private void omit(String path, String reason) {
            if (omissions.containsKey(path)) {
                return;
            }
            if (omissions.size() < 100) {
                omissions.put(path, reason);
            } else {
                suppressedOmissionCount++;
            }
        }

        private Map<String, Object> dataCompleteness() {
            List<Map<String, String>> omissionItems = omissions.entrySet().stream().map(entry -> {
                Map<String, String> item = new LinkedHashMap<>();
                item.put("path", entry.getKey());
                item.put("reason", entry.getValue());
                return item;
            }).toList();
            Map<String, Object> completeness = new LinkedHashMap<>();
            completeness.put("truncatedSections", List.copyOf(truncatedSections));
            completeness.put("omissions", omissionItems);
            completeness.put("omissionsTruncated", suppressedOmissionCount > 0);
            completeness.put("suppressedOmissionCount", suppressedOmissionCount);
            return completeness;
        }

        private void observeSourceFields(Map<String, Object> source, String path) {
            if (source == null) {
                return;
            }
            for (String key : source.keySet()) {
                String normalized = key.replace("_", "").toLowerCase(Locale.ROOT);
                if (KNOWN_SOURCE_FIELDS.contains(normalized) || DIRECT_IDENTIFIER_KEYS.contains(normalized)
                        || METADATA_KEYS.contains(normalized) || normalized.endsWith("id")) {
                    continue;
                }
                omit(path + "." + key, "UNMAPPED_FIELD");
            }
        }

        private String graphAlias(String nodeType, String nodeId, boolean customer) {
            if (customer) {
                return "P-1";
            }
            String type = nodeType == null ? "" : nodeType.replaceAll("[^A-Za-z0-9]", "")
                    .toUpperCase(Locale.ROOT);
            String businessId = graphBusinessId(nodeId);
            return switch (type) {
                case "PERSON" -> alias(graphPersonAliases, "P", businessId);
                case "ENTERPRISE" -> alias(enterpriseAliases, "E", qualifiedKey("enterpriseId", businessId));
                case "FAMILYMEMBER" -> alias(familyAliases, "F", qualifiedKey("familyMemberId", businessId));
                case "FAMILYPROFILE" -> alias(familyAliases, "F", qualifiedKey("familyProfileId", businessId));
                case "ORGANIZATION" -> alias(organizationAliases, "O", qualifiedKey("socialOrganizationId", businessId));
                case "EVENT" -> alias(eventAliases, "V", nodeId);
                case "MARKETSEGMENT" -> alias(marketSegmentAliases, "M", nodeId);
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
