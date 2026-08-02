package com.privatebank.business.dto.admin;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record ConfigurationCandidateRequest(
        @NotEmpty Map<@Size(max = 128) String, Object> configuration) {
}
