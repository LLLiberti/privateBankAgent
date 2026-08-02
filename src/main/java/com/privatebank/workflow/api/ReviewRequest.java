package com.privatebank.workflow.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReviewRequest(
        @NotBlank @Size(max = 64) String cfsArtifactId,
        @NotBlank @Size(max = 64) String complianceArtifactId,
        @NotNull Decision decision,
        @Size(max = 2000) String comment) {

    public enum Decision {
        APPROVE,
        REVISE,
        REJECT
    }
}
