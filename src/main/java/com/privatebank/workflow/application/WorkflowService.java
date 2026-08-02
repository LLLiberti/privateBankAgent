package com.privatebank.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.common.api.PageResponse;
import com.privatebank.common.exception.BusinessException;
import com.privatebank.common.exception.ErrorCode;
import com.privatebank.common.idempotency.IdempotencyExecutor;
import com.privatebank.customer.repository.CustomerDataRepository;
import com.privatebank.document.application.FileStorageService;
import com.privatebank.security.CurrentUserPrincipal;
import com.privatebank.security.CurrentUserService;
import com.privatebank.workflow.api.AgentStateResponse;
import com.privatebank.workflow.api.ArtifactRefResponse;
import com.privatebank.workflow.api.CancelRequest;
import com.privatebank.workflow.api.CreateWorkflowRequest;
import com.privatebank.workflow.api.OutputRetryRequest;
import com.privatebank.workflow.api.OutputStatusResponse;
import com.privatebank.workflow.api.ReviewRequest;
import com.privatebank.workflow.api.ReviewResponse;
import com.privatebank.workflow.api.WorkflowCreatedResponse;
import com.privatebank.workflow.api.WorkflowDetailResponse;
import com.privatebank.workflow.api.WorkflowInputRequest;
import com.privatebank.workflow.api.WorkflowResultResponse;
import com.privatebank.workflow.domain.AgentArtifact;
import com.privatebank.workflow.domain.AgentState;
import com.privatebank.workflow.domain.AgentStatus;
import com.privatebank.workflow.domain.AgentType;
import com.privatebank.workflow.domain.ReviewStatus;
import com.privatebank.workflow.domain.WorkflowReview;
import com.privatebank.workflow.domain.WorkflowState;
import com.privatebank.workflow.domain.WorkflowStatus;
import com.privatebank.workflow.repository.AgentArtifactRepository;
import com.privatebank.workflow.repository.AgentStateRepository;
import com.privatebank.workflow.repository.WorkflowReviewRepository;
import com.privatebank.workflow.repository.WorkflowStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WorkflowService {

    private final WorkflowStateRepository workflowRepository;
    private final AgentStateRepository agentStateRepository;
    private final AgentArtifactRepository artifactRepository;
    private final WorkflowReviewRepository reviewRepository;
    private final CustomerDataRepository customerDataRepository;
    private final CurrentUserService currentUserService;
    private final IdempotencyExecutor idempotencyExecutor;
    private final WorkflowEventHub eventHub;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final FileStorageService fileStorageService;

    @Transactional
    public WorkflowCreatedResponse create(
            CurrentUserPrincipal principal, String idempotencyKey, CreateWorkflowRequest request) {
        String key = principal.userId() + ":workflow:create:" + request.customerId() + ":" + idempotencyKey;
        return idempotencyExecutor.execute(key, () -> createOnce(principal, request));
    }

    private WorkflowCreatedResponse createOnce(CurrentUserPrincipal principal, CreateWorkflowRequest request) {
        currentUserService.requireCustomerAccess(principal, request.customerId());
        if (customerDataRepository.findSummary(request.customerId()).isEmpty()) {
            throw notFound("客户不存在");
        }
        if (request.asOfDate().isAfter(java.time.LocalDate.now())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_ARGUMENT, "asOfDate不能晚于当前日期");
        }
        LocalDateTime now = LocalDateTime.now();
        WorkflowState workflow = new WorkflowState();
        workflow.setWorkflowId("WF-" + UUID.randomUUID());
        workflow.setPersonId(request.customerId());
        workflow.setCreatedBy(principal.userId());
        workflow.setAsOfDate(request.asOfDate());
        workflow.setTemplateId(request.templateId());
        workflow.setAnalysisRequirements(request.analysisRequirements());
        workflow.setWorkflowStatus(WorkflowStatus.CREATED);
        workflow.setVersion(0L);
        workflow.setCreatedAt(now);
        workflow.setUpdatedAt(now);
        workflowRepository.save(workflow);

        List<AgentState> states = new ArrayList<>();
        for (AgentType type : AgentType.values()) {
            AgentState state = new AgentState();
            state.setAgentStateId("AS-" + UUID.randomUUID());
            state.setWorkflowId(workflow.getWorkflowId());
            state.setAgentType(type);
            state.setAgentStatus(type == AgentType.CUSTOMER_INSIGHT ? AgentStatus.READY : AgentStatus.PENDING);
            state.setRetryCount(0);
            state.setVersion(0L);
            states.add(state);
        }
        agentStateRepository.saveAll(states);
        afterCommit(() -> {
            eventPublisher.publishEvent(new WorkflowCreatedEvent(workflow.getWorkflowId()));
            eventHub.publish(workflow.getWorkflowId(), "WORKFLOW_CREATED",
                    Map.of("workflowId", workflow.getWorkflowId(), "status", workflow.getWorkflowStatus()));
        });
        return new WorkflowCreatedResponse(workflow.getWorkflowId(), workflow.getWorkflowStatus());
    }

    @Transactional(readOnly = true)
    public WorkflowDetailResponse detail(CurrentUserPrincipal principal, String workflowId) {
        WorkflowState workflow = requireAccessible(principal, workflowId);
        List<AgentStateResponse> states = agentStateRepository.findByWorkflowIdOrderByAgentType(workflowId)
                .stream().map(AgentStateResponse::from).toList();
        return WorkflowDetailResponse.from(workflow, states);
    }

    @Transactional(readOnly = true)
    public PageResponse<ArtifactRefResponse> artifacts(
            CurrentUserPrincipal principal, String workflowId, AgentType agentType, int pageNo, int pageSize) {
        requireAccessible(principal, workflowId);
        var pageable = PageRequest.of(pageNo - 1, pageSize, Sort.by(Sort.Direction.DESC, "createTime"));
        var page = agentType == null
                ? artifactRepository.findByWorkflowId(workflowId, pageable)
                : artifactRepository.findByWorkflowIdAndAgentType(workflowId, agentType, pageable);
        return PageResponse.of(page.getContent().stream().map(ArtifactRefResponse::from).toList(),
                page.getTotalElements(), pageNo, pageSize);
    }

    @Transactional
    public WorkflowDetailResponse provideInput(
            CurrentUserPrincipal principal,
            String workflowId,
            String idempotencyKey,
            WorkflowInputRequest request) {
        String key = principal.userId() + ":workflow:input:" + workflowId + ":"
                + request.currentArtifactId() + ":" + request.action() + ":" + idempotencyKey;
        return idempotencyExecutor.execute(key, () -> provideInputOnce(principal, workflowId, request));
    }

    private WorkflowDetailResponse provideInputOnce(
            CurrentUserPrincipal principal, String workflowId, WorkflowInputRequest request) {
        WorkflowState workflow = requireAccessible(principal, workflowId);
        if (workflow.getWorkflowStatus() != WorkflowStatus.WAITING_INPUT) {
            throw conflict("当前工作流不接受人工输入");
        }
        AgentArtifact current = artifactRepository.findById(request.currentArtifactId())
                .orElseThrow(() -> notFound("当前Artifact不存在"));
        if (!workflowId.equals(current.getWorkflowId()) || current.getAgentType() != AgentType.CUSTOMER_INSIGHT) {
            throw new BusinessException(HttpStatus.CONFLICT, ErrorCode.STALE_ARTIFACT, "当前Artifact不属于该工作流的KYC结果");
        }
        if (request.action() == WorkflowInputRequest.Action.CONTINUE) {
            ready(workflowId, AgentType.MARKET_INSIGHT, true);
            ready(workflowId, AgentType.PRODUCT_EXPERT, true);
        } else {
            ready(workflowId, AgentType.CUSTOMER_INSIGHT, true);
        }
        workflow.setWorkflowStatus(WorkflowStatus.RUNNING);
        workflow.setUpdatedAt(LocalDateTime.now());
        workflowRepository.save(workflow);
        afterCommit(() -> eventHub.publish(workflowId, "WORKFLOW_INPUT_PROVIDED",
                Map.of("workflowId", workflowId, "status", workflow.getWorkflowStatus(), "action", request.action())));
        return detail(principal, workflowId);
    }

    @Transactional
    public ReviewResponse review(
            CurrentUserPrincipal principal, String workflowId, String idempotencyKey, ReviewRequest request) {
        String key = principal.userId() + ":workflow:review:" + workflowId + ":"
                + request.cfsArtifactId() + ":" + request.decision() + ":" + idempotencyKey;
        return idempotencyExecutor.execute(key, () -> reviewOnce(principal, workflowId, request));
    }

    private ReviewResponse reviewOnce(
            CurrentUserPrincipal principal, String workflowId, ReviewRequest request) {
        WorkflowState workflow = requireAccessible(principal, workflowId);
        if (workflow.getWorkflowStatus() != WorkflowStatus.WAITING_REVIEW) {
            throw conflict("当前工作流不允许审核");
        }
        AgentArtifact cfs = requireArtifact(workflowId, request.cfsArtifactId(), AgentType.SOLUTION_DESIGN);
        AgentArtifact compliance = requireArtifact(
                workflowId, request.complianceArtifactId(), AgentType.COMPLIANCE_CHECK);
        if (!"PASS".equals(compliance.getComplianceResult())) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    ErrorCode.COMPLIANCE_BLOCKED, "合规结果不是PASS，不能审核通过");
        }
        verifyComplianceTargetsCfs(compliance, cfs);

        int round = reviewRepository.findFirstByWorkflowIdOrderByReviewRoundDesc(workflowId)
                .map(review -> review.getReviewRound() + 1)
                .orElse(1);
        WorkflowReview review = new WorkflowReview();
        review.setWorkflowId(workflowId);
        review.setReviewerId(principal.userId());
        review.setCfsArtifactId(cfs.getArtifactId());
        review.setReviewStatus(request.decision() == ReviewRequest.Decision.APPROVE
                ? ReviewStatus.APPROVED
                : ReviewStatus.REJECTED);
        review.setReviewComments(request.comment());
        review.setReviewRound(round);
        review.setVersion(0L);
        review.setReviewTime(LocalDateTime.now());
        reviewRepository.save(review);

        if (request.decision() == ReviewRequest.Decision.APPROVE) {
            workflow.setWorkflowStatus(WorkflowStatus.GENERATING_OUTPUT);
        } else {
            workflow.setWorkflowStatus(WorkflowStatus.RUNNING);
            ready(workflowId, AgentType.SOLUTION_DESIGN, true);
        }
        workflow.setUpdatedAt(LocalDateTime.now());
        workflowRepository.save(workflow);
        afterCommit(() -> eventHub.publish(workflowId,
                request.decision() == ReviewRequest.Decision.APPROVE ? "REVIEW_APPROVED" : "REVIEW_REJECTED",
                Map.of("workflowId", workflowId, "reviewRound", round, "status", workflow.getWorkflowStatus())));
        return new ReviewResponse(review.getReviewStatus(), workflow.getWorkflowStatus(), round);
    }

    @Transactional
    public WorkflowCreatedResponse cancel(
            CurrentUserPrincipal principal, String workflowId, String idempotencyKey, CancelRequest request) {
        String key = principal.userId() + ":workflow:cancel:" + workflowId + ":" + idempotencyKey;
        return idempotencyExecutor.execute(key, () -> cancelOnce(principal, workflowId, request));
    }

    private WorkflowCreatedResponse cancelOnce(
            CurrentUserPrincipal principal, String workflowId, CancelRequest request) {
        WorkflowState workflow = requireAccessible(principal, workflowId);
        if (workflow.getWorkflowStatus() == WorkflowStatus.CANCELED) {
            return new WorkflowCreatedResponse(workflowId, WorkflowStatus.CANCELED);
        }
        if (workflow.getWorkflowStatus().isTerminal()) {
            throw conflict("终态工作流不能取消");
        }
        workflow.setWorkflowStatus(WorkflowStatus.CANCELED);
        workflow.setErrorCode("USER_CANCELED");
        workflow.setErrorMessage(request.reason());
        workflow.setFinishTime(LocalDateTime.now());
        workflow.setUpdatedAt(LocalDateTime.now());
        workflowRepository.save(workflow);
        afterCommit(() -> eventHub.publish(workflowId, "WORKFLOW_CANCELED",
                Map.of("workflowId", workflowId, "status", WorkflowStatus.CANCELED)));
        return new WorkflowCreatedResponse(workflowId, WorkflowStatus.CANCELED);
    }

    @Transactional
    public OutputStatusResponse retryOutput(
            CurrentUserPrincipal principal,
            String workflowId,
            String idempotencyKey,
            OutputRetryRequest request) {
        String formats = request.failedFormats().stream().map(Enum::name).sorted().reduce((a, b) -> a + "," + b).orElse("");
        String key = principal.userId() + ":workflow:output-retry:" + workflowId + ":" + formats + ":" + idempotencyKey;
        return idempotencyExecutor.execute(key, () -> {
            WorkflowState workflow = requireAccessible(principal, workflowId);
            if (workflow.getWorkflowStatus() != WorkflowStatus.GENERATING_OUTPUT
                    && workflow.getWorkflowStatus() != WorkflowStatus.COMPLETED) {
                throw conflict("当前工作流不能重试输出");
            }
            afterCommit(() -> eventHub.publish(workflowId, "OUTPUT_RETRY_REQUESTED",
                    Map.of("workflowId", workflowId, "formats", request.failedFormats())));
            return new OutputStatusResponse("ACCEPTED", workflow.getWorkflowStatus(),
                    request.failedFormats().stream().map(Enum::name).toList());
        });
    }

    @Transactional(readOnly = true)
    public WorkflowResultResponse result(CurrentUserPrincipal principal, String workflowId) {
        WorkflowState workflow = requireAccessible(principal, workflowId);
        if (workflow.getWorkflowStatus() != WorkflowStatus.GENERATING_OUTPUT
                && workflow.getWorkflowStatus() != WorkflowStatus.COMPLETED) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    ErrorCode.PRECONDITION_FAILED, "工作流尚未产生最终结果");
        }
        AgentArtifact cfs = artifactRepository.findFirstByWorkflowIdAndAgentTypeOrderByVersionDesc(
                        workflowId, AgentType.SOLUTION_DESIGN)
                .orElseThrow(() -> notFound("最终CFS不存在"));
        return new WorkflowResultResponse(cfs.getArtifactId(), cfs.getVersion(), extractFiles(cfs));
    }

    @Transactional(readOnly = true)
    public DownloadFile download(
            CurrentUserPrincipal principal, String workflowId, String fileId) {
        WorkflowResultResponse result = result(principal, workflowId);
        Map<String, Object> file = result.files().stream()
                .filter(item -> fileId.equals(String.valueOf(item.get("fileId"))))
                .findFirst()
                .orElseThrow(() -> notFound("输出文件不存在"));
        String pathValue = String.valueOf(file.get("path"));
        Path path = fileStorageService.resolveStoredFile(pathValue);
        String fileName = String.valueOf(file.getOrDefault("fileName", path.getFileName().toString()));
        String contentType = String.valueOf(file.getOrDefault("contentType", "application/octet-stream"));
        return new DownloadFile(new FileSystemResource(path), fileName, contentType);
    }

    @Transactional(readOnly = true)
    public SseEmitter subscribe(CurrentUserPrincipal principal, String workflowId) {
        requireAccessible(principal, workflowId);
        return eventHub.subscribe(workflowId);
    }

    public WorkflowState requireAccessible(CurrentUserPrincipal principal, String workflowId) {
        WorkflowState workflow = workflowRepository.findById(workflowId)
                .orElseThrow(() -> notFound("工作流不存在"));
        currentUserService.requireCustomerAccess(principal, workflow.getPersonId());
        return workflow;
    }

    private AgentArtifact requireArtifact(String workflowId, String artifactId, AgentType type) {
        AgentArtifact artifact = artifactRepository.findById(artifactId)
                .orElseThrow(() -> notFound("Artifact不存在"));
        if (!workflowId.equals(artifact.getWorkflowId()) || artifact.getAgentType() != type) {
            throw new BusinessException(HttpStatus.CONFLICT, ErrorCode.STALE_ARTIFACT, "Artifact与当前工作流或阶段不匹配");
        }
        return artifact;
    }

    private void verifyComplianceTargetsCfs(AgentArtifact compliance, AgentArtifact cfs) {
        if (!StringUtils.hasText(compliance.getResult())) {
            throw new BusinessException(HttpStatus.CONFLICT, ErrorCode.STALE_ARTIFACT, "合规结果缺少CFS版本引用");
        }
        try {
            JsonNode result = objectMapper.readTree(compliance.getResult());
            String cfsArtifactId = result.path("cfsArtifactId").asText();
            if (!cfs.getArtifactId().equals(cfsArtifactId)) {
                throw new BusinessException(HttpStatus.CONFLICT, ErrorCode.STALE_ARTIFACT, "合规结果与被审核CFS版本不一致");
            }
        } catch (JsonProcessingException exception) {
            throw new BusinessException(HttpStatus.CONFLICT, ErrorCode.STALE_ARTIFACT, "合规结果格式无效");
        }
    }

    private void ready(String workflowId, AgentType type, boolean newExecution) {
        AgentState state = agentStateRepository.findByWorkflowIdAndAgentType(workflowId, type)
                .orElseThrow(() -> notFound("Agent状态不存在"));
        state.setAgentStatus(AgentStatus.READY);
        state.setErrorCode(null);
        state.setErrorMessage(null);
        state.setStartTime(null);
        state.setFinishTime(null);
        if (newExecution) {
            state.setExecutionId("EXE-" + UUID.randomUUID());
        }
        agentStateRepository.save(state);
    }

    private List<Map<String, Object>> extractFiles(AgentArtifact artifact) {
        if (!StringUtils.hasText(artifact.getResult())) {
            return List.of();
        }
        try {
            JsonNode files = objectMapper.readTree(artifact.getResult()).path("files");
            if (!files.isArray()) {
                return List.of();
            }
            return objectMapper.convertValue(files, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            return List.of();
        }
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

    private BusinessException notFound(String message) {
        return new BusinessException(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, message);
    }

    private BusinessException conflict(String message) {
        return new BusinessException(HttpStatus.CONFLICT, ErrorCode.STATE_CONFLICT, message);
    }

    public record DownloadFile(Resource resource, String fileName, String contentType) {
    }
}
