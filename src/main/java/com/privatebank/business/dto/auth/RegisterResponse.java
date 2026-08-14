package com.privatebank.business.dto.auth;

import com.privatebank.business.enums.auth.RoleName;

public record RegisterResponse(
        String userId,
        String account,
        String userName,
        RoleName role) {
}
