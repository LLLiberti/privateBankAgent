package com.privatebank.business.service.admin;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.privatebank.business.common.exception.BusinessException;
import com.privatebank.business.common.exception.ErrorCode;
import com.privatebank.business.common.idempotency.IdempotencyExecutor;
import com.privatebank.business.dto.admin.CustomerManagerResponse;
import com.privatebank.business.dto.admin.CustomerScopeResponse;
import com.privatebank.business.dto.admin.ReplaceCustomerScopesRequest;
import com.privatebank.business.dto.admin.ReplaceCustomerScopesResponse;
import com.privatebank.business.dto.common.PageResponse;
import com.privatebank.business.entity.auth.SysRole;
import com.privatebank.business.entity.auth.SysUser;
import com.privatebank.business.entity.auth.UserCustomerScope;
import com.privatebank.business.enums.auth.RoleName;
import com.privatebank.business.mapper.auth.SysRoleMapper;
import com.privatebank.business.mapper.auth.SysUserMapper;
import com.privatebank.business.mapper.auth.UserCustomerScopeMapper;
import com.privatebank.business.mapper.customer.CustomerDataMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CustomerScopeAdminService {

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final UserCustomerScopeMapper scopeMapper;
    private final CustomerDataMapper customerDataMapper;
    private final IdempotencyExecutor idempotencyExecutor;

    @Transactional(readOnly = true)
    public PageResponse<CustomerManagerResponse> customerManagers(String keyword, int pageNo, int pageSize) {
        SysRole customerManagerRole = requireCustomerManagerRole();
        String normalizedKeyword = StringUtils.hasText(keyword) ? keyword.trim() : null;
        var query = Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getRoleId, customerManagerRole.getRoleId())
                .and(normalizedKeyword != null, wrapper -> wrapper
                        .like(SysUser::getUserAccount, normalizedKeyword)
                        .or()
                        .like(SysUser::getUserName, normalizedKeyword))
                .orderByAsc(SysUser::getUserName)
                .orderByAsc(SysUser::getUserId);
        Page<SysUser> page = userMapper.selectPage(new Page<>(pageNo, pageSize), query);
        List<CustomerManagerResponse> items = page.getRecords().stream()
                .map(user -> CustomerManagerResponse.from(user, activeScopeCount(user.getUserId())))
                .toList();
        return PageResponse.of(items, page.getTotal(), pageNo, pageSize);
    }

    @Transactional(readOnly = true)
    public PageResponse<CustomerScopeResponse> customerScopes(
            String userId, boolean includeInactive, int pageNo, int pageSize) {
        requireCustomerManager(userId);
        var query = Wrappers.<UserCustomerScope>lambdaQuery()
                .eq(UserCustomerScope::getUserId, userId)
                .eq(!includeInactive, UserCustomerScope::getScopeStatus, 1)
                .orderByDesc(UserCustomerScope::getUpdatedAt)
                .orderByDesc(UserCustomerScope::getScopeId);
        Page<UserCustomerScope> page = scopeMapper.selectPage(new Page<>(pageNo, pageSize), query);
        return PageResponse.of(page.getRecords().stream().map(CustomerScopeResponse::from).toList(),
                page.getTotal(), pageNo, pageSize);
    }

    @Transactional
    public ReplaceCustomerScopesResponse replaceCustomerScopes(
            String userId, String idempotencyKey, ReplaceCustomerScopesRequest request) {
        String businessKey = "admin:customer-manager-scope:" + userId + ":" + idempotencyKey;
        return idempotencyExecutor.execute(businessKey, () -> replaceCustomerScopesOnce(userId, request));
    }

    private ReplaceCustomerScopesResponse replaceCustomerScopesOnce(
            String userId, ReplaceCustomerScopesRequest request) {
        requireCustomerManagerForUpdate(userId);
        Set<Long> desiredCustomerIds = normalizeCustomerIds(request);
        verifyCustomersExist(desiredCustomerIds);

        List<UserCustomerScope> existingScopes = scopeMapper.selectList(Wrappers.<UserCustomerScope>lambdaQuery()
                .eq(UserCustomerScope::getUserId, userId));
        Map<Long, UserCustomerScope> scopesByCustomerId = existingScopes.stream()
                .collect(Collectors.toMap(UserCustomerScope::getPersonId, Function.identity()));

        for (UserCustomerScope scope : existingScopes) {
            int expectedStatus = desiredCustomerIds.contains(scope.getPersonId()) ? 1 : 0;
            if (!Integer.valueOf(expectedStatus).equals(scope.getScopeStatus())) {
                scope.setScopeStatus(expectedStatus);
                updateScope(scope);
            }
        }

        for (Long customerId : desiredCustomerIds) {
            if (!scopesByCustomerId.containsKey(customerId)) {
                UserCustomerScope scope = new UserCustomerScope();
                scope.setUserId(userId);
                scope.setPersonId(customerId);
                scope.setScopeStatus(1);
                scope.setCreatedAt(LocalDateTime.now());
                scope.setUpdatedAt(LocalDateTime.now());
                insertScope(scope);
            }
        }

        List<Long> assignedCustomerIds = new ArrayList<>(desiredCustomerIds);
        assignedCustomerIds.sort(Comparator.naturalOrder());
        return new ReplaceCustomerScopesResponse(userId, assignedCustomerIds.size(), assignedCustomerIds);
    }

    private SysRole requireCustomerManagerRole() {
        SysRole role = roleMapper.selectOne(Wrappers.<SysRole>lambdaQuery()
                .eq(SysRole::getRoleName, RoleName.CUSTOMER_MANAGER.name()));
        if (role == null) {
            throw unavailable("客户经理角色未配置");
        }
        return role;
    }

    private SysUser requireCustomerManager(String userId) {
        SysUser user = userMapper.selectById(userId);
        return assertCustomerManager(user);
    }

    private SysUser requireCustomerManagerForUpdate(String userId) {
        SysUser user = userMapper.selectByIdForUpdate(userId);
        return assertCustomerManager(user);
    }

    private SysUser assertCustomerManager(SysUser user) {
        if (user == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, "客户经理不存在");
        }
        SysRole role = roleMapper.selectById(user.getRoleId());
        if (role == null || role.getRoleName() == null || !RoleName.CUSTOMER_MANAGER.name().equals(role.getRoleName())) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.PRECONDITION_FAILED,
                    "目标用户不是客户经理");
        }
        return user;
    }

    private Set<Long> normalizeCustomerIds(ReplaceCustomerScopesRequest request) {
        if (request == null || request.customerIds() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_ARGUMENT, "customerIds不能为空");
        }
        if (request.customerIds().size() > 500) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_ARGUMENT, "单次最多分配500位客户");
        }
        LinkedHashSet<Long> customerIds = new LinkedHashSet<>();
        for (Long customerId : request.customerIds()) {
            if (customerId == null || customerId <= 0) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_ARGUMENT, "customerIds必须为正整数");
            }
            if (!customerIds.add(customerId)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_ARGUMENT, "customerIds不能包含重复客户");
            }
        }
        return customerIds;
    }

    private void verifyCustomersExist(Set<Long> customerIds) {
        if (customerIds.isEmpty()) {
            return;
        }
        if (customerDataMapper.countExistingCustomers(List.copyOf(customerIds)) != customerIds.size()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, "存在不存在的客户");
        }
    }

    private long activeScopeCount(String userId) {
        return scopeMapper.selectCount(Wrappers.<UserCustomerScope>lambdaQuery()
                .eq(UserCustomerScope::getUserId, userId)
                .eq(UserCustomerScope::getScopeStatus, 1));
    }

    private void insertScope(UserCustomerScope scope) {
        if (scopeMapper.insert(scope) != 1) {
            throw unavailable("客户可见范围保存失败");
        }
    }

    private void updateScope(UserCustomerScope scope) {
        if (scopeMapper.updateById(scope) != 1) {
            throw unavailable("客户可见范围更新失败");
        }
    }

    private BusinessException unavailable(String message) {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.SERVICE_UNAVAILABLE, message);
    }
}
