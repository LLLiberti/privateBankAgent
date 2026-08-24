package com.privatebank.business.service.admin;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.business.common.exception.BusinessException;
import com.privatebank.business.common.exception.ErrorCode;
import com.privatebank.business.common.idempotency.IdempotencyExecutor;
import com.privatebank.business.dto.admin.AdminWorkflowDeleteRequest;
import com.privatebank.business.dto.admin.AdminWorkflowDeleteResponse;
import com.privatebank.business.entity.workflow.AgentArtifact;
import com.privatebank.business.entity.workflow.AgentState;
import com.privatebank.business.entity.workflow.WorkflowReview;
import com.privatebank.business.entity.workflow.WorkflowState;
import com.privatebank.business.mapper.workflow.AgentArtifactMapper;
import com.privatebank.business.mapper.workflow.AgentStateMapper;
import com.privatebank.business.mapper.workflow.WorkflowReviewMapper;
import com.privatebank.business.mapper.workflow.WorkflowStateMapper;
import com.privatebank.business.security.CurrentUserPrincipal;
import com.privatebank.business.service.document.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminWorkflowCleanupService {

    private final WorkflowStateMapper workflowMapper;
    private final AgentStateMapper agentStateMapper;
    private final AgentArtifactMapper artifactMapper;
    private final WorkflowReviewMapper reviewMapper;
    private final IdempotencyExecutor idempotencyExecutor;
    private final ObjectMapper objectMapper;
    private final FileStorageService fileStorageService;

    @Transactional
    public AdminWorkflowDeleteResponse delete(
            CurrentUserPrincipal principal,
            String workflowId,
            String idempotencyKey,
            AdminWorkflowDeleteRequest request) {
        String key = principal.userId() + ":admin:workflow:delete:" + workflowId + ":" + idempotencyKey;
        return idempotencyExecutor.execute(key, () -> deleteOnce(principal, workflowId, request));
    }

    private AdminWorkflowDeleteResponse deleteOnce(
            CurrentUserPrincipal principal,
            String workflowId,
            AdminWorkflowDeleteRequest request) {
        WorkflowState workflow = workflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND,
                    "CFS任务不存在");
        }
        if (!request.expectedVersion().equals(workflow.getVersion())) {
            throw new BusinessException(HttpStatus.CONFLICT, ErrorCode.STATE_CONFLICT,
                    "CFS任务已被其他请求更新，请刷新后重试");
        }

        var artifacts = artifactMapper.selectList(
                Wrappers.<AgentArtifact>lambdaQuery()
                        .eq(AgentArtifact::getWorkflowId, workflowId)
                        .orderByAsc(AgentArtifact::getArtifactId));
        Set<String> reportPaths = reportPaths(artifacts);

        int deletedReviews = reviewMapper.delete(Wrappers.<WorkflowReview>lambdaQuery()
                .eq(WorkflowReview::getWorkflowId, workflowId));
        int deletedArtifacts = artifactMapper.delete(Wrappers.<AgentArtifact>lambdaQuery()
                .eq(AgentArtifact::getWorkflowId, workflowId));
        int deletedAgentStates = agentStateMapper.delete(Wrappers.<AgentState>lambdaQuery()
                .eq(AgentState::getWorkflowId, workflowId));
        int deletedWorkflow = workflowMapper.delete(Wrappers.<WorkflowState>lambdaQuery()
                .eq(WorkflowState::getWorkflowId, workflowId)
                .eq(WorkflowState::getVersion, request.expectedVersion()));
        if (deletedWorkflow != 1) {
            throw new BusinessException(HttpStatus.CONFLICT, ErrorCode.STATE_CONFLICT,
                    "CFS任务已被其他请求更新，请刷新后重试");
        }

        String reason = normalizedReason(request.reason());
        afterCommit(() -> {
            reportPaths.forEach(fileStorageService::deleteQuietly);
            log.info("adminCfsWorkflowDeleted traceId={} operatorUserId={} workflowId={} previousStatus={} "
                            + "reason={} agentStateCount={} artifactCount={} reviewCount={} fileCleanupCount={}",
                    MDC.get("traceId"), principal.userId(), workflowId, workflow.getWorkflowStatus(),
                    safeLogValue(reason), deletedAgentStates, deletedArtifacts, deletedReviews, reportPaths.size());
        });

        return new AdminWorkflowDeleteResponse(
                workflowId,
                true,
                deletedAgentStates,
                deletedArtifacts,
                deletedReviews,
                reportPaths.isEmpty() ? "NOT_REQUIRED" : "BEST_EFFORT");
    }

    private Set<String> reportPaths(List<AgentArtifact> artifacts) {
        Set<String> paths = new LinkedHashSet<>();
        for (AgentArtifact artifact : artifacts) {
            if (StringUtils.hasText(artifact.getStorageKey())) {
                paths.add(artifact.getStorageKey());
            }
            if (!StringUtils.hasText(artifact.getResult())) {
                continue;
            }
            try {
                JsonNode files = objectMapper.readTree(artifact.getResult()).path("files");
                if (!files.isArray()) {
                    continue;
                }
                for (JsonNode file : files) {
                    String path = file.path("path").asText(null);
                    if (StringUtils.hasText(path)) {
                        paths.add(path);
                    }
                }
            } catch (JsonProcessingException ignored) {
                // Invalid historical artifact JSON must not prevent test data cleanup.
            }
        }
        return Set.copyOf(paths);
    }

    private String normalizedReason(String reason) {
        return StringUtils.hasText(reason) ? reason.trim() : "";
    }

    private String safeLogValue(String value) {
        return value.replace('\r', '_').replace('\n', '_');
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
