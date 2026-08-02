package com.privatebank.security;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.privatebank.auth.domain.RoleName;
import com.privatebank.auth.domain.SysRole;
import com.privatebank.auth.domain.SysUser;
import com.privatebank.auth.mapper.SysRoleMapper;
import com.privatebank.auth.mapper.SysUserMapper;
import com.privatebank.auth.mapper.UserCustomerScopeMapper;
import com.privatebank.auth.domain.UserCustomerScope;
import com.privatebank.common.exception.BusinessException;
import com.privatebank.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CurrentUserService {

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final UserCustomerScopeMapper scopeMapper;

    public CurrentUserPrincipal load(String userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHENTICATED, "用户不存在");
        }
        SysRole role = roleMapper.selectById(user.getRoleId());
        if (role == null) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ErrorCode.ACCESS_DENIED, "用户角色不存在");
        }
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
        if (scopeMapper.selectCount(Wrappers.<UserCustomerScope>lambdaQuery()
                .eq(UserCustomerScope::getUserId, principal.userId())
                .eq(UserCustomerScope::getPersonId, personId)
                .eq(UserCustomerScope::getScopeStatus, 1)) == 0) {
            throw new BusinessException(
                    HttpStatus.FORBIDDEN, ErrorCode.CUSTOMER_OUT_OF_SCOPE, "客户不在当前用户授权范围内");
        }
    }

    public long activeScopeCount(CurrentUserPrincipal principal) {
        return principal.isSystemAdmin() ? -1 : scopeMapper.selectCount(Wrappers.<UserCustomerScope>lambdaQuery()
                .eq(UserCustomerScope::getUserId, principal.userId())
                .eq(UserCustomerScope::getScopeStatus, 1));
    }
}
