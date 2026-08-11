package com.privatebank.business.service.auth;

import com.privatebank.business.common.exception.BusinessException;
import com.privatebank.business.common.exception.ErrorCode;
import com.privatebank.business.dto.auth.RegisterRequest;
import com.privatebank.business.dto.auth.RegisterResponse;
import com.privatebank.business.entity.auth.SysRole;
import com.privatebank.business.entity.auth.SysUser;
import com.privatebank.business.enums.auth.RoleName;
import com.privatebank.business.mapper.auth.SysRoleMapper;
import com.privatebank.business.mapper.auth.SysUserMapper;
import com.privatebank.business.security.CurrentUserPrincipal;
import com.privatebank.business.security.CurrentUserService;
import com.privatebank.business.security.JwtService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private SysUserMapper userMapper;
    @Mock
    private SysRoleMapper roleMapper;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private CurrentUserService currentUserService;
    @Mock
    private JwtService jwtService;
    @InjectMocks
    private AuthService authService;

    @Test
    void registersCustomerManagerWithoutAuthenticatedPrincipal() {
        when(userMapper.selectOne(any())).thenReturn(null);
        when(roleMapper.selectOne(any())).thenReturn(role("ROLE-CUSTOMER-MANAGER", RoleName.CUSTOMER_MANAGER));
        when(passwordEncoder.encode("SecurePass1")).thenReturn("encoded-password");

        RegisterResponse response = authService.register(
                new RegisterRequest(" manager-01 ", " Alice ", "SecurePass1", RoleName.CUSTOMER_MANAGER), null);

        ArgumentCaptor<SysUser> userCaptor = ArgumentCaptor.forClass(SysUser.class);
        verify(userMapper).insert(userCaptor.capture());
        SysUser user = userCaptor.getValue();
        assertThat(response.role()).isEqualTo(RoleName.CUSTOMER_MANAGER);
        assertThat(user.getUserId()).isNotBlank();
        assertThat(user.getUserAccount()).isEqualTo("manager-01");
        assertThat(user.getUserName()).isEqualTo("Alice");
        assertThat(user.getPasswordHash()).isEqualTo("encoded-password");
        assertThat(user.getRoleId()).isEqualTo("ROLE-CUSTOMER-MANAGER");
    }

    @Test
    void rejectsAnonymousSystemAdminRegistration() {
        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("admin-01", "Admin", "SecurePass1", RoleName.SYSTEM_ADMIN), null))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(exception.getCode()).isEqualTo(ErrorCode.ACCESS_DENIED);
                });

        verify(userMapper, never()).selectOne(any());
        verify(userMapper, never()).insert(any(SysUser.class));
    }

    @Test
    void allowsSystemAdminToRegisterSystemAdmin() {
        when(userMapper.selectOne(any())).thenReturn(null);
        when(roleMapper.selectOne(any())).thenReturn(role("ROLE-SYSTEM-ADMIN", RoleName.SYSTEM_ADMIN));
        when(passwordEncoder.encode("SecurePass1")).thenReturn("encoded-password");

        authService.register(
                new RegisterRequest("admin-01", "Admin", "SecurePass1", RoleName.SYSTEM_ADMIN),
                new CurrentUserPrincipal("admin-id", "Current admin", RoleName.SYSTEM_ADMIN));

        ArgumentCaptor<SysUser> userCaptor = ArgumentCaptor.forClass(SysUser.class);
        verify(userMapper).insert(userCaptor.capture());
        assertThat(userCaptor.getValue().getRoleId()).isEqualTo("ROLE-SYSTEM-ADMIN");
    }

    @Test
    void rejectsExistingAccount() {
        when(userMapper.selectOne(any())).thenReturn(new SysUser());

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("manager-01", "Alice", "SecurePass1", RoleName.CUSTOMER_MANAGER), null))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        verify(userMapper, never()).insert(any(SysUser.class));
    }

    private SysRole role(String roleId, RoleName roleName) {
        SysRole role = new SysRole();
        role.setRoleId(roleId);
        role.setRoleName(roleName.name());
        return role;
    }
}
