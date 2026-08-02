package com.privatebank.business.dto.auth;

import com.privatebank.business.entity.auth.RoleName;

import java.util.List;

public record UserProfileResponse(
        String userId,
        String userName,
        RoleName role,
        List<String> permissions,
        DataScopeSummary dataScopeSummary) {

    public record DataScopeSummary(boolean allCustomers, long customerCount) {
    }
}
