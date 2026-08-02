package com.privatebank.business.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Pattern(regexp = "DEMO_PASSWORD") String loginType,
        @NotBlank @Size(max = 64) String account,
        @NotBlank @Size(max = 200) String password,
        @Size(max = 128) String clientNonce) {
}
