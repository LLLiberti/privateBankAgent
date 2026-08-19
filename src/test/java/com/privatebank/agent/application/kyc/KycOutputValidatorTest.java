package com.privatebank.agent.application.kyc;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.privatebank.agent.domain.kyc.KycMaskedInput;
import com.privatebank.agent.domain.kyc.KycOutputValidationException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KycOutputValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final KycOutputValidator validator = new KycOutputValidator(objectMapper);

    @Test
    void acceptsTenFollowUpQuestions() throws Exception {
        String output = outputWithQuestions(java.util.stream.IntStream.rangeClosed(1, 10)
                .mapToObj(index -> question("Q-" + index, "confirm item " + index))
                .toList());

        assertThatCode(() -> validator.validate(output, input()))
                .doesNotThrowAnyException();
    }


    @Test
    void rejectsElevenFollowUpQuestions() throws Exception {
        String output = outputWithQuestions(java.util.stream.IntStream.rangeClosed(1, 11)
                .mapToObj(index -> question("Q-" + index, "confirm item " + index))
                .toList());

        assertThatThrownBy(() -> validator.validate(output, input()))
                .isInstanceOf(KycOutputValidationException.class)
                .hasMessageContaining("\u6700\u591a 10");
    }

    @Test
    void rejectsDuplicateFollowUpQuestionIds() throws Exception {
        String output = outputWithQuestions(List.of(
                question("Q-1", "confirm liquidity"),
                question("Q-1", "confirm cross-border allocation")));

        assertThatThrownBy(() -> validator.validate(output, input()))
                .isInstanceOf(KycOutputValidationException.class)
                .hasMessageContaining("id \u4e0d\u80fd\u91cd\u590d");
    }


    private String outputWithQuestions(List<Map<String, String>> questions) throws Exception {
        Map<String, Object> graphAssessment = Map.of(
                "contribution", "NOT_AVAILABLE",
                "summary", "no graph",
                "evidenceRefs", List.of());
        Map<String, Object> output = Map.of(
                "riskLevel", "LOW",
                "summary", "analysis based on current evidence",
                "findings", List.of(),
                "riskAlerts", List.of(),
                "recommendedActions", List.of(),
                "dataGaps", List.of(),
                "followUpQuestions", questions,
                "graphAssessment", graphAssessment);
        return objectMapper.writeValueAsString(output);
    }

    private Map<String, String> question(String id, String text) {
        return Map.of("id", id, "question", text);
    }


    private KycMaskedInput input() {
        return new KycMaskedInput(
                Map.of("relationshipGraph", Map.of(
                        "available", false,
                        "relationshipCount", 0,
                        "evidenceRefs", List.of(),
                        "relationships", List.of())),
                Map.of("SRC-1", 1L),
                Set.of(),
                Set.of(),
                Map.of(),
                "a".repeat(64));
    }
}
