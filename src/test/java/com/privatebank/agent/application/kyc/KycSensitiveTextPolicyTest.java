package com.privatebank.agent.application.kyc;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class KycSensitiveTextPolicyTest {

    @Test
    void redactsOnlyTheAddressSpanAndRetainsBusinessMeaning() {
        String sanitized = KycSensitiveTextPolicy.redactDirectIdentifiers(
                "客户在北京市朝阳区建国路88号办公，偏好长期投资");

        assertThat(sanitized)
                .contains("[LOCATION_REDACTED]", "偏好长期投资")
                .doesNotContain("建国路88号");
        assertThat(KycSensitiveTextPolicy.redactDirectIdentifiers("会面地点望京SOHO，讨论传承"))
                .contains("[LOCATION_REDACTED]", "讨论传承")
                .doesNotContain("望京SOHO");
    }

    @Test
    void doesNotTreatBusinessWordsEndingInRoadAsAddresses() {
        assertThat(KycSensitiveTextPolicy.containsDirectIdentifier("需要核验跨记录风险链路")).isFalse();
        assertThat(KycSensitiveTextPolicy.containsDirectIdentifier("形成完整关系链路和处置思路")).isFalse();
        assertThat(KycSensitiveTextPolicy.containsDirectIdentifier("北京市朝阳区建国路")).isTrue();
        assertThat(KycSensitiveTextPolicy.containsDirectIdentifier("办公地点建国路88号")).isTrue();
    }

    @Test
    void appliesBoundariesToAsciiAndNumericProhibitedTerms() {
        assertThat(KycSensitiveTextPolicy.containsProhibitedTerm("Johnson", Set.of("John"))).isFalse();
        assertThat(KycSensitiveTextPolicy.containsProhibitedTerm("John", Set.of("John"))).isTrue();
        assertThat(KycSensitiveTextPolicy.replaceTerm(
                "股票000001，金额1000001元", "000001", "[CODE_REDACTED]"))
                .contains("股票[CODE_REDACTED]", "金额1000001元")
                .doesNotContain("金额1[CODE_REDACTED]元");
    }

    @Test
    void distinguishesRoleInstructionsFromExplicitlyLabeledNames() {
        assertThat(KycSensitiveTextPolicy.containsDirectIdentifier("客户经理应核验资金来源")).isFalse();
        assertThat(KycSensitiveTextPolicy.containsDirectIdentifier("联系人:张三")).isTrue();
    }
}
