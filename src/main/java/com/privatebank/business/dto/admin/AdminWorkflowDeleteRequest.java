package com.privatebank.business.dto.admin;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record AdminWorkflowDeleteRequest(
        @Size(max = 500) String reason,
        @NotNull @PositiveOrZero Long expectedVersion) {
}
