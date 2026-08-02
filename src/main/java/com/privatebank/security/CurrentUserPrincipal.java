package com.privatebank.security;

import com.privatebank.auth.domain.RoleName;

public record CurrentUserPrincipal(String userId, String userName, RoleName role) {

    public boolean isSystemAdmin() {
        return role == RoleName.SYSTEM_ADMIN;
    }
}
