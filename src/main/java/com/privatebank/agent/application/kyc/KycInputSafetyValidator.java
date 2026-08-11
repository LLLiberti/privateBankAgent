package com.privatebank.agent.application.kyc;

import com.privatebank.agent.domain.kyc.KycInputValidationException;

import java.time.temporal.TemporalAccessor;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Final egress guard for the model boundary.  The masking service builds the
 * projection, while this class makes an accidental raw-text field fail closed.
 */
public final class KycInputSafetyValidator {

    private static final Set<String> FORBIDDEN_KEYS = Set.of(
            "fullname", "displayname", "membername", "protectedalias", "enterprisename",
            "organizationname", "counterpartname", "activityname", "partnername", "publishername",
            "stockcode", "headquarters", "address", "residence", "nativeplace", "birthplace",
            "school", "email", "phone", "idnumber", "description", "text", "rawtext",
            "notetext", "riskdescription", "eventdescription", "relationdescription",
            "memberdescription", "arrangementdescription", "candidatedescription", "title");
    private static final Pattern CONTROLLED_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,79}");
    private static final Pattern SOURCE_REF = Pattern.compile("SRC-[1-9][0-9]*");
    private static final Pattern ENTITY_ALIAS = Pattern.compile("[PEFOC]-[1-9][0-9]*");
    private static final Pattern VERSION = Pattern.compile("kyc-input\\.v[0-9]+");
    private static final Pattern DATE_OR_PERIOD = Pattern.compile("[0-9]{4}(-[0-9]{2}(-[0-9]{2})?)?");
    private static final Pattern UNIT_OR_CURRENCY = Pattern.compile("[A-Z]{3}(_[0-9]+[A-Z])?");

    public void validate(Map<String, Object> payload, Set<String> prohibitedTerms) {
        validateValue(payload, "root", prohibitedTerms == null ? Set.of() : prohibitedTerms);
    }

    @SuppressWarnings("unchecked")
    private void validateValue(Object value, String fieldName, Set<String> prohibitedTerms) {
        if (value == null || value instanceof Number || value instanceof Boolean
                || value instanceof TemporalAccessor || value instanceof Date) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new KycInputValidationException("KYC 脱敏输入包含非文本字段名");
                }
                String normalized = normalize(key);
                if (FORBIDDEN_KEYS.contains(normalized)) {
                    throw new KycInputValidationException("KYC 脱敏输入包含禁止字段: " + key);
                }
                validateValue(entry.getValue(), key, prohibitedTerms);
            }
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                validateValue(item, fieldName, prohibitedTerms);
            }
            return;
        }
        if (value instanceof String text) {
            validateText(text, fieldName, prohibitedTerms);
            return;
        }
        throw new KycInputValidationException("KYC 脱敏输入包含不支持的字段类型: " + fieldName);
    }

    private void validateText(String text, String fieldName, Set<String> prohibitedTerms) {
        if (text.isBlank() || text.length() > 80 || !isControlledValue(text, fieldName)) {
            throw new KycInputValidationException("KYC 脱敏输入包含非受控文本: " + fieldName);
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        boolean leaked = prohibitedTerms.stream()
                .filter(term -> term != null && term.trim().length() >= 2)
                .map(term -> term.trim().toLowerCase(Locale.ROOT))
                .anyMatch(normalized::contains);
        if (leaked) {
            throw new KycInputValidationException("KYC 脱敏输入包含原始标识信息");
        }
    }

    private boolean isControlledValue(String value, String fieldName) {
        return CONTROLLED_CODE.matcher(value).matches()
                || SOURCE_REF.matcher(value).matches()
                || ENTITY_ALIAS.matcher(value).matches()
                || VERSION.matcher(value).matches()
                || DATE_OR_PERIOD.matcher(value).matches()
                || (normalize(fieldName).contains("unit") || normalize(fieldName).contains("currency"))
                && UNIT_OR_CURRENCY.matcher(value).matches();
    }

    private String normalize(String key) {
        return key.replace("_", "").toLowerCase(Locale.ROOT);
    }
}
