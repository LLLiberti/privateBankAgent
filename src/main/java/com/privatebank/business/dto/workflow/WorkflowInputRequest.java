package com.privatebank.business.dto.workflow;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record WorkflowInputRequest(
        @NotNull Action action,
        @NotBlank @Size(max = 64) String currentArtifactId,
        @Size(max = 2000) String description,
        List<@Size(max = 128) String> confirmedItems) {

    public enum Action {
        CONTINUE,
        SUPPLEMENT,
        REGENERATE
    }
}
