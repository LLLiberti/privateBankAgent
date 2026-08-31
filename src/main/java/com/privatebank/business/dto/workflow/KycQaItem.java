package com.privatebank.business.dto.workflow;

/**
 * A sanitized question/answer pair used for KYC clarification.
 * Raw manager input must be masked before crossing the model boundary.
 */
public record KycQaItem(String questionId, String question, String answer) {

    public KycQaItem {
        questionId = questionId == null || questionId.isBlank() ? null : questionId.trim();
        question = question == null || question.isBlank() ? null : question.trim();
        answer = answer == null || answer.isBlank() ? null : answer.trim();
    }

    public boolean hasAnswer() {
        return answer != null && !answer.isBlank();
    }
}
