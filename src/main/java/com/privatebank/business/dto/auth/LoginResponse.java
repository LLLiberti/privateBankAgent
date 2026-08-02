package com.privatebank.business.dto.auth;

import java.time.Instant;

public record LoginResponse(String accessToken, Instant expiresAt, UserProfileResponse user) {
}
