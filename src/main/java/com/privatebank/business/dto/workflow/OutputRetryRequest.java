package com.privatebank.business.dto.workflow;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record OutputRetryRequest(@NotEmpty List<Format> failedFormats) {

    public enum Format {
        WORD,
        PDF,
        MARKDOWN
    }
}
