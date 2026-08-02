package com.privatebank.auth.application;

import com.privatebank.auth.api.LoginRequest;
import com.privatebank.auth.api.LoginResponse;
import com.privatebank.auth.api.UserProfileResponse;
import com.privatebank.auth.domain.RoleName;
import com.privatebank.auth.domain.SysUser;
import com.privatebank.auth.repository.SysUserRepository;
import com.privatebank.common.exception.BusinessException;
import com.privatebank.common.exception.ErrorCode;
import com.privatebank.security.CurrentUserPrincipal;
import com.privatebank.security.CurrentUserService;
import com.privatebank.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final CurrentUserService currentUserService;
    private final JwtService jwtService;

    public LoginResponse login(LoginRequest request) {
        String account = request.account().trim();
        SysUser user = userRepository.findByUserAccountIgnoreCase(account)
                .orElseThrow(this::invalidCredentials);
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
