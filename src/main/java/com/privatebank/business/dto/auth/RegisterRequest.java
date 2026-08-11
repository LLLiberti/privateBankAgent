package com.privatebank.business.dto.auth;

import com.privatebank.business.enums.auth.RoleName;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Size(max = 64) String account,
        @NotBlank @Size(max = 100) String userName,
        @NotBlank @Size(min = 8, max = 200) String password,
        @NotNull RoleName role) {
}
