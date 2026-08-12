package com.privatebank.business.dto.admin;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ReplaceCustomerScopesRequest(
        @NotNull @Size(max = 500) List<@NotNull @Positive Long> customerIds) {
}
