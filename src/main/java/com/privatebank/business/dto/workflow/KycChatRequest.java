package com.privatebank.business.dto.workflow;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** One customer-manager message sent to the runtime-only KYC chat session. */
public record KycChatRequest(
        @NotNull @Positive Long personId,
        @NotBlank @Size(max = 64) String kycArtifactId,
        @Size(max = 64) String sessionId,
        @NotBlank @Size(max = 600) String message) {
}
