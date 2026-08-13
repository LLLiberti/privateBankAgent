package com.privatebank.business.dto.workflow;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * description and confirmedItems are runtime-only inputs for REGENERATE. They
 * are not stored in any workflow or KYC artifact.
 */
public record WorkflowInputRequest(
        @NotNull Action action,
        @NotBlank @Size(max = 64) String currentArtifactId,
        @Size(max = 2000) String description,
        List<@Size(max = 128) String> confirmedItems) {

    public enum Action {
        /** Customer manager approves the KYC analysis and releases downstream Agents. */
        CONTINUE,
        /** Regenerate KYC with the runtime-only manager supplement in this request. */
        SUPPLEMENT,
        /** Regenerate KYC; optionally accepts another runtime-only supplement. */
        REGENERATE
    }
}
