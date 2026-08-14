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

    private static final Set<String> DIRECT_IDENTIFIER_KEYS = Set.of(
            "fullname", "displayname", "membername", "protectedalias", "enterprisename",
            "organizationname", "counterpartname", "activityname", "partnername", "publishername",
            "stockcode", "school", "schoolname", "email", "phone", "mobile", "idnumber", "accountnumber",
            "bankaccount", "birthdate", "nativeplace", "birthplace", "residence", "rawtext");
    private static final Pattern EMAIL = Pattern.compile("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?86[- ]?)?1[3-9]\\d{9}(?!\\d)");
    private static final Pattern ID_NUMBER = Pattern.compile("(?<![0-9A-Z])\\d{17}[0-9Xx](?![0-9A-Z])");
    private static final Pattern BANK_ACCOUNT = Pattern.compile("(?<!\\d)\\d{16,19}(?!\\d)");
    private static final Pattern PRECISE_ADDRESS = Pattern.compile(
            ".*(?:[\\p{IsHan}]{2,12}(?:路|街|巷|弄)\\d*号?|\\d+号|栋|幢|单元|室|大厦|小区|公寓).*");

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
                validateValue(entry.getValue(), childPath, prohibitedTerms,
                        normalized.contains("address") || normalized.contains("headquarters"));
            }
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            int index = 0;
            for (Object item : iterable) {
                validateValue(item, fieldPath + "[" + index + "]", prohibitedTerms, false);
                index++;
            }
            return;
        }
        if (value instanceof String text) {
            validateText(text, fieldPath, prohibitedTerms, false);
            return;
        }
        throw new KycInputValidationException("KYC 脱敏输入包含不支持的字段类型: " + fieldPath);
    }

    private void validateValue(
            Object value, String fieldPath, Set<String> prohibitedTerms, boolean preciseLocationField) {
        if (value instanceof String text) {
            validateText(text, fieldPath, prohibitedTerms, preciseLocationField);
            return;
        }
        validateValue(value, fieldPath, prohibitedTerms);
    }

    private void validateText(
            String text, String fieldPath, Set<String> prohibitedTerms, boolean preciseLocationField) {
        if (text.isBlank() || text.length() > 600) {
            throw new KycInputValidationException("KYC 脱敏输入包含非法文本: " + fieldPath);
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        boolean leaked = prohibitedTerms.stream()
                .filter(term -> term != null && term.trim().length() >= 2)
                .map(term -> term.trim().toLowerCase(Locale.ROOT))
                .anyMatch(normalized::contains);
        if (leaked) {
            throw new KycInputValidationException("KYC 脱敏输入包含原始标识信息: " + fieldPath);
        }
        if (EMAIL.matcher(text).find() || PHONE.matcher(text).find() || ID_NUMBER.matcher(text).find()
                || BANK_ACCOUNT.matcher(text).find()
                || preciseLocationField && PRECISE_ADDRESS.matcher(text).matches()) {
            throw new KycInputValidationException("KYC 脱敏输入仍包含格式化敏感信息: " + fieldPath);
        }
    }

    private String normalize(String key) {
        return key.replace("_", "").toLowerCase(Locale.ROOT);
    }
}
