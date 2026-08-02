package com.privatebank.business.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ConfigurationPublishRequest(@NotBlank @Size(max = 64) String candidateId) {
}
