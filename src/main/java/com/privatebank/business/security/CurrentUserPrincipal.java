package com.privatebank.business.security;

import com.privatebank.business.entity.auth.RoleName;

public record CurrentUserPrincipal(String userId, String userName, RoleName role) {

    public boolean isSystemAdmin() {
        return role == RoleName.SYSTEM_ADMIN;
    }
}
