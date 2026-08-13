package com.privatebank.business.dto.admin;

import com.privatebank.business.entity.auth.SysUser;

import java.time.LocalDateTime;

public record CustomerManagerResponse(
        String userId,
        String account,
        String userName,
        long assignedCustomerCount,
        LocalDateTime updatedAt) {

    public static CustomerManagerResponse from(SysUser user, long assignedCustomerCount) {
        return new CustomerManagerResponse(
                user.getUserId(), user.getUserAccount(), user.getUserName(), assignedCustomerCount, user.getUpdatedAt());
    }
}
