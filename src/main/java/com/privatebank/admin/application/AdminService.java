package com.privatebank.admin.application;

import com.privatebank.admin.api.AdminWorkflowResponse;
import com.privatebank.common.api.PageResponse;
import com.privatebank.common.exception.BusinessException;
import com.privatebank.common.exception.ErrorCode;
import com.privatebank.workflow.domain.WorkflowStatus;
import com.privatebank.workflow.repository.WorkflowStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.info.BuildProperties;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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

    private final WorkflowStateRepository workflowRepository;
    private final ConfigurationRegistry configurationRegistry;
    private final Optional<BuildProperties> buildProperties;

    @Transactional(readOnly = true)
    public Map<String, Object> overview() {
        return Map.of(
                "application", "private-bank-backend",
                "version", buildProperties.map(BuildProperties::getVersion).orElse("development"),
                "activeWorkflows", workflowRepository.countByWorkflowStatusIn(ACTIVE_STATUSES),
                "failedWorkflows", workflowRepository.countByWorkflowStatusIn(List.of(WorkflowStatus.FAILED)),
                "configurationTypes", configurationRegistry.supportedTypes(),
                "indexStatus", "NOT_CONFIGURED");
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminWorkflowResponse> workflows(
            WorkflowStatus status, int pageNo, int pageSize) {
        var pageable = PageRequest.of(pageNo - 1, pageSize, Sort.by(Sort.Direction.DESC, "updatedAt"));
        var page = status == null ? workflowRepository.findAll(pageable) : workflowRepository.findByWorkflowStatus(status, pageable);
        return PageResponse.of(page.getContent().stream().map(AdminWorkflowResponse::from).toList(),
                page.getTotalElements(), pageNo, pageSize);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> validateReset(String confirmation) {
        if (!"RESET_DEMO_DATA".equals(confirmation)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_ARGUMENT, "二次确认文本不正确");
        }
        long active = workflowRepository.countByWorkflowStatusIn(ACTIVE_STATUSES);
        if (active > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, ErrorCode.STATE_CONFLICT, "存在运行中的工作流，不能重置演示数据");
        }
        throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.PRECONDITION_FAILED,
                "尚未配置演示基线数据包，未执行任何删除操作");
    }
}
