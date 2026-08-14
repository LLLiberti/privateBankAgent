package com.privatebank.business.service.admin;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.privatebank.business.dto.admin.AdminWorkflowResponse;
import com.privatebank.business.dto.common.PageResponse;
import com.privatebank.business.common.exception.BusinessException;
import com.privatebank.business.common.exception.ErrorCode;
import com.privatebank.business.enums.workflow.WorkflowStatus;
import com.privatebank.business.entity.workflow.WorkflowState;
import com.privatebank.business.mapper.workflow.WorkflowStateMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AdminService {

    private static final List<WorkflowStatus> ACTIVE_STATUSES = List.of(
            WorkflowStatus.CREATED, WorkflowStatus.RUNNING, WorkflowStatus.WAITING_INPUT,
            WorkflowStatus.WAITING_REVIEW, WorkflowStatus.GENERATING_OUTPUT);

    private final WorkflowStateMapper workflowMapper;
    private final ConfigurationRegistry configurationRegistry;
    private final Optional<BuildProperties> buildProperties;

    @Transactional(readOnly = true)
    public Map<String, Object> overview() {
        return Map.of(
                "application", "private-bank-backend",
                "version", buildProperties.map(BuildProperties::getVersion).orElse("development"),
                "activeWorkflows", countByStatuses(ACTIVE_STATUSES),
                "failedWorkflows", countByStatuses(List.of(WorkflowStatus.FAILED)),
                "configurationTypes", configurationRegistry.supportedTypes(),
                "indexStatus", "NOT_CONFIGURED");
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminWorkflowResponse> workflows(
            WorkflowStatus status, int pageNo, int pageSize) {
        var query = Wrappers.<WorkflowState>lambdaQuery()
                .eq(status != null, WorkflowState::getWorkflowStatus, status)
                .orderByDesc(WorkflowState::getUpdatedAt);
        Page<WorkflowState> page = workflowMapper.selectPage(new Page<>(pageNo, pageSize), query);
        return PageResponse.of(page.getRecords().stream().map(AdminWorkflowResponse::from).toList(),
                page.getTotal(), pageNo, pageSize);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> validateReset(String confirmation) {
        if (!"RESET_DEMO_DATA".equals(confirmation)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_ARGUMENT, "二次确认文本不正确");
        }
        long active = countByStatuses(ACTIVE_STATUSES);
        if (active > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, ErrorCode.STATE_CONFLICT, "存在运行中的工作流，不能重置演示数据");
        }
        throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.PRECONDITION_FAILED,
                "尚未配置演示基线数据包，未执行任何删除操作");
    }

    private long countByStatuses(List<WorkflowStatus> statuses) {
        return workflowMapper.selectCount(Wrappers.<WorkflowState>lambdaQuery()
                .in(WorkflowState::getWorkflowStatus, statuses));
    }
}
