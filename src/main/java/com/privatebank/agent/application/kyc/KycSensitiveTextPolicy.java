package com.privatebank.agent.application.kyc;

import com.privatebank.agent.domain.kyc.KycInputValidationException;
import com.privatebank.agent.domain.kyc.KycOutputValidationException;

import java.text.Normalizer;
import java.util.Collection;
import java.util.Locale;
import java.util.regex.Pattern;

/** Shared direct-identifier rules for both sides of the model boundary. */
public final class KycSensitiveTextPolicy {

    private static final String ZERO_WIDTH = "[\\u200B-\\u200F\\u2060\\uFEFF]";
    private static final Pattern EMAIL = Pattern.compile(
            "(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b");
    private static final Pattern MOBILE_PHONE = Pattern.compile(
            "(?<!\\d)(?:\\+?86[- ]?)?1[3-9](?:[- ]?\\d){9}(?!\\d)");
    private static final Pattern LANDLINE_PHONE = Pattern.compile(
            "(?<!\\d)(?:\\+?86[- ]?)?0\\d{2,3}[- ]?\\d{7,8}(?:[- 转]\\d{1,6})?(?!\\d)");
    private static final Pattern INTERNATIONAL_PHONE = Pattern.compile(
            "(?<!\\d)\\+[1-9]\\d{0,2}(?:[- ]?\\d){6,14}(?!\\d)");
    private static final Pattern MAINLAND_ID = Pattern.compile(
            "(?<![0-9A-Z])(?:\\d{15}|\\d{17}[0-9X])(?![0-9A-Z])", Pattern.CASE_INSENSITIVE);
    private static final Pattern PASSPORT_OR_TRAVEL_DOCUMENT = Pattern.compile(
            "(?<![A-Z0-9])(?:[EGPDS]\\d{7,8}|[A-Z]{1,2}\\d{6}\\(?[0-9A]\\)?|[A-Z][12]\\d{8})(?![A-Z0-9])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BANK_ACCOUNT = Pattern.compile(
            "(?<!\\d)(?:\\d[- ]?){15,18}\\d(?!\\d)");
    private static final Pattern LABELED_PERSON_NAME = Pattern.compile(
            "(?:(?:姓名|联系人|客户经理)|(?:配偶|子女)(?:姓名)?)"
                    + "\\s*(?:[:：]|为|是|叫)\\s*[\\p{IsHan}·]{2,8}");
    private static final Pattern PRECISE_STREET_ADDRESS = Pattern.compile(
            "(?:[\\p{IsHan}]{2,12}(?:省|自治区|特别行政区|市|区|县)){0,4}"
                    + "[\\p{IsHan}A-Za-z0-9]{1,20}(?:路|街|巷|弄)\\s*\\d{0,6}号?"
                    + "(?:\\s*\\d{1,6}(?:栋|幢|单元|室))?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PRECISE_LANDMARK = Pattern.compile(
            "[\\p{IsHan}A-Za-z0-9]{2,30}(?:SOHO|广场|园区|大厦|小区|公寓)"
                    + "(?:\\s*\\d{1,6}(?:号|栋|幢|单元|室))?",
            Pattern.CASE_INSENSITIVE);

    private KycSensitiveTextPolicy() {
    }

    public static String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll(ZERO_WIDTH, "")
                .replace('—', '-')
                .replace('–', '-');
    }

    public static String redactDirectIdentifiers(String value) {
        String sanitized = normalizeText(value);
        sanitized = EMAIL.matcher(sanitized).replaceAll("[EMAIL_REDACTED]");
        sanitized = MOBILE_PHONE.matcher(sanitized).replaceAll("[PHONE_REDACTED]");
        sanitized = LANDLINE_PHONE.matcher(sanitized).replaceAll("[PHONE_REDACTED]");
        sanitized = INTERNATIONAL_PHONE.matcher(sanitized).replaceAll("[PHONE_REDACTED]");
        sanitized = MAINLAND_ID.matcher(sanitized).replaceAll("[ID_REDACTED]");
        sanitized = PASSPORT_OR_TRAVEL_DOCUMENT.matcher(sanitized).replaceAll("[ID_REDACTED]");
        sanitized = BANK_ACCOUNT.matcher(sanitized).replaceAll("[ACCOUNT_REDACTED]");
        sanitized = LABELED_PERSON_NAME.matcher(sanitized).replaceAll("[PERSON_REDACTED]");
        sanitized = PRECISE_STREET_ADDRESS.matcher(sanitized).replaceAll("[LOCATION_REDACTED]");
        sanitized = PRECISE_LANDMARK.matcher(sanitized).replaceAll("[LOCATION_REDACTED]");
        return sanitized;
    }

    public static boolean containsDirectIdentifier(String value) {
        return directIdentifierCategory(value) != null;
    }

    public static boolean containsProhibitedTerm(String value, Collection<String> terms) {
        if (value == null || terms == null || terms.isEmpty()) {
            return false;
        }
        String normalized = normalizeText(value).toLowerCase(Locale.ROOT);
        String compact = compact(normalized);
        for (String term : terms) {
            if (term == null || term.isBlank()) {
                continue;
            }
            String normalizedTerm = normalizeText(term.trim()).toLowerCase(Locale.ROOT);
            String compactTerm = compact(normalizedTerm);
            if (compactTerm.isEmpty()) {
                continue;
            }
            if (compactTerm.chars().allMatch(Character::isDigit)) {
                if (Pattern.compile("(?<!\\d)" + Pattern.quote(compactTerm) + "(?!\\d)")
                        .matcher(compact).find()) {
                    return true;
                }
            } else if (compactTerm.chars().allMatch(character -> character < 128
                    && Character.isLetterOrDigit(character))) {
                if (Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(compactTerm)
                                + "(?![\\p{L}\\p{N}])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                        .matcher(compact).find()) {
                    return true;
                }
            } else if (compact.contains(compactTerm)) {
                return true;
            }
        }
        return false;
    }

    public static String replaceTerm(String value, String term, String replacement) {
        if (value == null || term == null || term.isBlank()) {
            return value;
        }
        String normalizedTerm = normalizeText(term.trim());
        StringBuilder expression = new StringBuilder();
        boolean numeric = normalizedTerm.chars().allMatch(Character::isDigit);
        boolean asciiWord = normalizedTerm.chars().allMatch(character -> character < 128
                && Character.isLetterOrDigit(character));
        if (numeric) {
            expression.append("(?<!\\d)");
        } else if (asciiWord) {
            expression.append("(?<![\\p{L}\\p{N}])");
        }
        int[] codePoints = normalizedTerm.codePoints().toArray();
        for (int index = 0; index < codePoints.length; index++) {
            if (index > 0) {
                expression.append(numeric ? "[-\\s]*" : "[\\s\\u200B-\\u200F\\u2060\\uFEFF]*");
            }
            expression.append(Pattern.quote(new String(Character.toChars(codePoints[index]))));
        }
        if (numeric) {
            expression.append("(?!\\d)");
        } else if (asciiWord) {
            expression.append("(?![\\p{L}\\p{N}])");
        }
        return Pattern.compile(expression.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                .matcher(normalizeText(value))
                .replaceAll(java.util.regex.Matcher.quoteReplacement(replacement));
    }

    public static String truncateCodePoints(String value, int maxCodePoints) {
        if (value == null || value.codePointCount(0, value.length()) <= maxCodePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }

    public static void rejectInput(String value, String path, Collection<String> terms) {
        if (containsProhibitedTerm(value, terms)) {
            throw new KycInputValidationException("KYC 脱敏输入仍含直接标识信息: " + path);
        }
        String category = directIdentifierCategory(value);
        if (category != null) {
            throw new KycInputValidationException(
                    "KYC 脱敏输入仍含格式型直接标识信息(" + category + "): " + path);
        }
    }

    public static void rejectOutput(String value, Collection<String> terms) {
        if (containsProhibitedTerm(value, terms)) {
            throw new KycOutputValidationException("KYC 结果包含未脱敏直接标识信息(ENTITY_TERM)");
        }
        String category = directIdentifierCategory(value);
        if (category != null) {
            throw new KycOutputValidationException(
                    "KYC 结果包含未脱敏直接标识信息(" + category + ")");
        }
    }

    private static String directIdentifierCategory(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = normalizeText(value);
        if (EMAIL.matcher(normalized).find()) {
            return "EMAIL";
        }
        if (MOBILE_PHONE.matcher(normalized).find()
                || LANDLINE_PHONE.matcher(normalized).find()
                || INTERNATIONAL_PHONE.matcher(normalized).find()) {
            return "PHONE";
        }
        if (MAINLAND_ID.matcher(normalized).find() || PASSPORT_OR_TRAVEL_DOCUMENT.matcher(normalized).find()) {
            return "IDENTITY_DOCUMENT";
        }
        if (BANK_ACCOUNT.matcher(normalized).find()) {
            return "BANK_ACCOUNT";
        }
        if (LABELED_PERSON_NAME.matcher(normalized).find()) {
            return "LABELED_PERSON_NAME";
        }
        if (PRECISE_STREET_ADDRESS.matcher(normalized).find() || PRECISE_LANDMARK.matcher(normalized).find()) {
            return "PRECISE_LOCATION";
        }
        return null;
    }

    private static String compact(String value) {
        return value.replaceAll("[\\s\\-‐‑‒–—―·•,，.。()（）]+", "");
    }
}
