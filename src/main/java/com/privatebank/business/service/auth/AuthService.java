package com.privatebank.business.service.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.privatebank.business.dto.auth.LoginRequest;
import com.privatebank.business.dto.auth.LoginResponse;
import com.privatebank.business.dto.auth.UserProfileResponse;
import com.privatebank.business.entity.auth.RoleName;
import com.privatebank.business.entity.auth.SysUser;
import com.privatebank.business.mapper.auth.SysUserMapper;
import com.privatebank.business.common.exception.BusinessException;
import com.privatebank.business.common.exception.ErrorCode;
import com.privatebank.business.security.CurrentUserPrincipal;
import com.privatebank.business.security.CurrentUserService;
import com.privatebank.business.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final CurrentUserService currentUserService;
    private final JwtService jwtService;

    public LoginResponse login(LoginRequest request) {
        String account = request.account().trim();
        SysUser user = userMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .apply("LOWER(`userAccount`) = LOWER({0})", account));
        if (user == null) {
            throw invalidCredentials();
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw invalidCredentials();
        }
        CurrentUserPrincipal principal = currentUserService.load(user.getUserId());
        JwtService.IssuedToken token = jwtService.issue(principal.userId());
        return new LoginResponse(token.value(), token.expiresAt(), profile(principal));
    }

    public UserProfileResponse profile(CurrentUserPrincipal principal) {
        List<String> permissions = principal.role() == RoleName.SYSTEM_ADMIN
                ? List.of("ADMIN_CONFIG", "ADMIN_WORKFLOW", "CUSTOMER_READ", "PRODUCT_READ")
                : List.of("CUSTOMER_READ", "DOCUMENT_UPLOAD", "WORKFLOW_CREATE", "CFS_REVIEW", "FILE_DOWNLOAD");
        long count = currentUserService.activeScopeCount(principal);
        return new UserProfileResponse(
                principal.userId(),
                principal.userName(),
                principal.role(),
                permissions,
                new UserProfileResponse.DataScopeSummary(principal.isSystemAdmin(), count));
    }

    private BusinessException invalidCredentials() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHENTICATED, "账号或密码错误");
    }
}
