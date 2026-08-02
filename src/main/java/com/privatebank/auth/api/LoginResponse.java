package com.privatebank.auth.api;

import java.time.Instant;

public record LoginResponse(String accessToken, Instant expiresAt, UserProfileResponse user) {
}
