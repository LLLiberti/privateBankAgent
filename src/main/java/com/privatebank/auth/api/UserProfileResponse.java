package com.privatebank.auth.api;

import com.privatebank.auth.domain.RoleName;

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
