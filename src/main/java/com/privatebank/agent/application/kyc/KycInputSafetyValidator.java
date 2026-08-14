package com.privatebank.agent.application.kyc;

import com.privatebank.agent.domain.kyc.KycInputValidationException;

import java.time.temporal.TemporalAccessor;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Final egress guard for the model boundary.  The masking service builds the
 * projection, while this class makes an accidental raw-text field fail closed.
 */
public final class KycInputSafetyValidator {

    private static final Set<String> DIRECT_IDENTIFIER_KEYS = Set.of(
            "fullname", "displayname", "membername", "protectedalias", "enterprisename",
            "organizationname", "counterpartname", "partnername", "publishername",
            "stockcode", "school", "schoolname", "email", "phone", "mobile", "telephone", "fax",
            "idnumber", "identitynumber", "identityno", "passportnumber", "accountnumber",
            "bankaccount", "birthdate", "rawtext");

    public void validate(Map<String, Object> payload, Set<String> prohibitedTerms) {
        validateValue(payload, "root", prohibitedTerms == null ? Set.of() : prohibitedTerms);
    }

    @SuppressWarnings("unchecked")
    private void validateValue(Object value, String fieldPath, Set<String> prohibitedTerms) {
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
                String childPath = fieldPath + "." + key;
                if (DIRECT_IDENTIFIER_KEYS.contains(normalized)) {
                    throw new KycInputValidationException("KYC 脱敏输入包含禁止字段: " + childPath);
                }
                validateValue(entry.getValue(), childPath, prohibitedTerms);
            }
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            int index = 0;
            for (Object item : iterable) {
                validateValue(item, fieldPath + "[" + index + "]", prohibitedTerms);
                index++;
            }
            return;
        }
        if (value instanceof String text) {
            validateText(text, fieldPath, prohibitedTerms);
            return;
        }
        throw new KycInputValidationException("KYC 脱敏输入包含不支持的字段类型: " + fieldPath);
    }

    private void validateText(String text, String fieldPath, Set<String> prohibitedTerms) {
        if (text.isBlank() || text.codePointCount(0, text.length()) > 600) {
            throw new KycInputValidationException("KYC 脱敏输入包含非法文本: " + fieldPath);
        }
        KycSensitiveTextPolicy.rejectInput(text, fieldPath, prohibitedTerms);
    }

    private String normalize(String key) {
        return key.replace("_", "").toLowerCase(Locale.ROOT);
    }
}
