package com.privatebank.security;

import com.privatebank.auth.domain.RoleName;
import com.privatebank.auth.domain.SysRole;
import com.privatebank.auth.domain.SysUser;
import com.privatebank.auth.repository.SysRoleRepository;
import com.privatebank.auth.repository.SysUserRepository;
import com.privatebank.auth.repository.UserCustomerScopeRepository;
import com.privatebank.common.exception.BusinessException;
import com.privatebank.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CurrentUserService {

    private final SysUserRepository userRepository;
    private final SysRoleRepository roleRepository;
    private final UserCustomerScopeRepository scopeRepository;

    public CurrentUserPrincipal load(String userId) {
        SysUser user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHENTICATED, "用户不存在"));
        SysRole role = roleRepository.findById(user.getRoleId())
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.FORBIDDEN, ErrorCode.ACCESS_DENIED, "用户角色不存在"));
        RoleName roleName;
        try {
            roleName = RoleName.valueOf(role.getRoleName());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ErrorCode.ACCESS_DENIED, "用户角色无效");
        }
        return new CurrentUserPrincipal(user.getUserId(), user.getUserName(), roleName);
    }

    public void requireCustomerAccess(CurrentUserPrincipal principal, Long personId) {
        if (principal.isSystemAdmin()) {
            return;
        }
        if (!scopeRepository.existsByUserIdAndPersonIdAndScopeStatus(principal.userId(), personId, 1)) {
            throw new BusinessException(
                    HttpStatus.FORBIDDEN, ErrorCode.CUSTOMER_OUT_OF_SCOPE, "客户不在当前用户授权范围内");
        }
    }

    public long activeScopeCount(CurrentUserPrincipal principal) {
        return principal.isSystemAdmin() ? -1 : scopeRepository.countByUserIdAndScopeStatus(principal.userId(), 1);
    }
}
