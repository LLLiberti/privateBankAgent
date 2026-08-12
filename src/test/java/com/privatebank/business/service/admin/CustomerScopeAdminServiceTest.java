package com.privatebank.business.service.admin;

import com.privatebank.business.common.exception.BusinessException;
import com.privatebank.business.common.idempotency.IdempotencyExecutor;
import com.privatebank.business.dto.admin.ReplaceCustomerScopesRequest;
import com.privatebank.business.dto.admin.ReplaceCustomerScopesResponse;
import com.privatebank.business.entity.auth.RoleName;
import com.privatebank.business.entity.auth.SysRole;
import com.privatebank.business.entity.auth.SysUser;
import com.privatebank.business.entity.auth.UserCustomerScope;
import com.privatebank.business.mapper.auth.SysRoleMapper;
import com.privatebank.business.mapper.auth.SysUserMapper;
import com.privatebank.business.mapper.auth.UserCustomerScopeMapper;
import com.privatebank.business.mapper.customer.CustomerDataMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomerScopeAdminServiceTest {

    private final SysUserMapper userMapper = mock(SysUserMapper.class);
    private final SysRoleMapper roleMapper = mock(SysRoleMapper.class);
    private final UserCustomerScopeMapper scopeMapper = mock(UserCustomerScopeMapper.class);
    private final CustomerDataMapper customerDataMapper = mock(CustomerDataMapper.class);
    private final CustomerScopeAdminService service = new CustomerScopeAdminService(
            userMapper, roleMapper, scopeMapper, customerDataMapper, new IdempotencyExecutor(180));

    @Test
    void replaceScopesDisablesRemovedScopesReactivatesExistingAndCreatesNewScopes() {
        when(userMapper.selectByIdForUpdate("RM-001")).thenReturn(customerManager());
        when(roleMapper.selectById("ROLE-CUSTOMER-MANAGER")).thenReturn(customerManagerRole());
        when(customerDataMapper.countExistingCustomers(anyList())).thenReturn(2L);

        UserCustomerScope removed = scope(1L, 1);
        UserCustomerScope reactivated = scope(2L, 0);
        when(scopeMapper.selectList(any())).thenReturn(List.of(removed, reactivated));
        when(scopeMapper.updateById(any(UserCustomerScope.class))).thenReturn(1);
        when(scopeMapper.insert(any(UserCustomerScope.class))).thenReturn(1);

        ReplaceCustomerScopesResponse response = service.replaceCustomerScopes(
                "RM-001", "replace-001", new ReplaceCustomerScopesRequest(List.of(2L, 3L)));

        assertThat(response.userId()).isEqualTo("RM-001");
        assertThat(response.assignedCustomerCount()).isEqualTo(2);
        assertThat(response.customerIds()).containsExactly(2L, 3L);
        assertThat(removed.getScopeStatus()).isZero();
        assertThat(reactivated.getScopeStatus()).isOne();

        ArgumentCaptor<UserCustomerScope> insertedScope = ArgumentCaptor.forClass(UserCustomerScope.class);
        verify(scopeMapper).insert(insertedScope.capture());
        assertThat(insertedScope.getValue().getUserId()).isEqualTo("RM-001");
        assertThat(insertedScope.getValue().getPersonId()).isEqualTo(3L);
        assertThat(insertedScope.getValue().getScopeStatus()).isOne();
    }

    @Test
    void replaceScopesRejectsUserWhoIsNotCustomerManager() {
        SysUser administrator = new SysUser();
        administrator.setUserId("ADMIN-001");
        administrator.setRoleId("ROLE-SYSTEM-ADMIN");
        SysRole administratorRole = new SysRole();
        administratorRole.setRoleId("ROLE-SYSTEM-ADMIN");
        administratorRole.setRoleName(RoleName.SYSTEM_ADMIN.name());
        when(userMapper.selectByIdForUpdate("ADMIN-001")).thenReturn(administrator);
        when(roleMapper.selectById("ROLE-SYSTEM-ADMIN")).thenReturn(administratorRole);

        assertThatThrownBy(() -> service.replaceCustomerScopes(
                "ADMIN-001", "replace-002", new ReplaceCustomerScopesRequest(List.of())))
                .isInstanceOf(BusinessException.class)
                .hasMessage("目标用户不是客户经理");

        verify(scopeMapper, never()).selectList(any());
        verify(customerDataMapper, never()).countExistingCustomers(anyList());
    }

    @Test
    void replaceScopesRejectsDuplicateCustomerIds() {
        when(userMapper.selectByIdForUpdate("RM-001")).thenReturn(customerManager());
        when(roleMapper.selectById("ROLE-CUSTOMER-MANAGER")).thenReturn(customerManagerRole());

        assertThatThrownBy(() -> service.replaceCustomerScopes(
                "RM-001", "replace-003", new ReplaceCustomerScopesRequest(List.of(1L, 1L))))
                .isInstanceOf(BusinessException.class)
                .hasMessage("customerIds不能包含重复客户");

        verify(customerDataMapper, never()).countExistingCustomers(anyList());
    }

    private SysUser customerManager() {
        SysUser user = new SysUser();
        user.setUserId("RM-001");
        user.setRoleId("ROLE-CUSTOMER-MANAGER");
        user.setUserAccount("rm001");
        user.setUserName("客户经理");
        return user;
    }

    private SysRole customerManagerRole() {
        SysRole role = new SysRole();
        role.setRoleId("ROLE-CUSTOMER-MANAGER");
        role.setRoleName(RoleName.CUSTOMER_MANAGER.name());
        return role;
    }

    private UserCustomerScope scope(Long customerId, int status) {
        UserCustomerScope scope = new UserCustomerScope();
        scope.setScopeId(customerId);
        scope.setUserId("RM-001");
        scope.setPersonId(customerId);
        scope.setScopeStatus(status);
        return scope;
    }
}
