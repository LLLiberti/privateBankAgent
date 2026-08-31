package com.privatebank.business.service.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.privatebank.business.dto.workflow.KycQaItem;
import com.privatebank.business.dto.common.PageResponse;
import com.privatebank.business.common.exception.BusinessException;
import com.privatebank.business.common.exception.ErrorCode;
import com.privatebank.business.common.idempotency.IdempotencyExecutor;
import com.privatebank.business.mapper.customer.CustomerDataMapper;
import com.privatebank.business.service.document.FileStorageService;
import com.privatebank.business.security.CurrentUserPrincipal;
import com.privatebank.business.security.CurrentUserService;
import com.privatebank.business.dto.workflow.AgentStateResponse;
import com.privatebank.business.dto.workflow.ArtifactRefResponse;
import com.privatebank.business.dto.workflow.AvailableImportBatchResponse;
import com.privatebank.business.dto.workflow.CancelRequest;
import com.privatebank.business.dto.workflow.CfsReportCenterItemResponse;
import com.privatebank.business.dto.workflow.CfsReportFileResponse;
import com.privatebank.business.dto.workflow.CfsReportPreviewResponse;
import com.privatebank.business.dto.workflow.CfsReportWorkflowRow;
import com.privatebank.business.dto.workflow.CustomerInsightAnalysisResponse;
import com.privatebank.business.dto.workflow.CustomerInsightRetryRequest;
import com.privatebank.business.dto.workflow.CustomerManagerWorkflowResponse;
import com.privatebank.business.dto.workflow.CreateWorkflowRequest;
import com.privatebank.business.dto.workflow.OutputRetryRequest;
import com.privatebank.business.dto.workflow.OutputStatusResponse;
import com.privatebank.business.dto.workflow.ReviewRequest;
import com.privatebank.business.dto.workflow.ReviewResponse;
import com.privatebank.business.dto.workflow.WorkflowCreatedResponse;
import com.privatebank.business.dto.workflow.WorkflowDetailResponse;
import com.privatebank.business.dto.workflow.WorkflowInputRequest;
import com.privatebank.business.dto.workflow.WorkflowResultResponse;
import com.privatebank.business.entity.workflow.AgentArtifact;
import com.privatebank.business.entity.workflow.AgentState;
import com.privatebank.business.enums.workflow.AgentStatus;
import com.privatebank.business.enums.workflow.AgentType;
import com.privatebank.business.enums.workflow.CfsReportStatus;
import com.privatebank.business.enums.workflow.ReviewStatus;
import com.privatebank.business.entity.workflow.WorkflowReview;
import com.privatebank.business.entity.workflow.WorkflowState;
import com.privatebank.business.enums.workflow.WorkflowStatus;
import com.privatebank.business.mapper.workflow.AgentArtifactMapper;
import com.privatebank.business.mapper.workflow.AgentStateMapper;
import com.privatebank.business.mapper.workflow.ImportBatchMapper;
import com.privatebank.business.mapper.workflow.WorkflowReviewMapper;
import com.privatebank.business.mapper.workflow.WorkflowStateMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
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
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WorkflowService {

    private static final Set<String> RETRYABLE_CUSTOMER_INSIGHT_FAILURES = Set.of(
            "KYC_MODEL_CALL_FAILED",
            "KYC_OUTPUT_CONTRACT_INVALID");

    private final WorkflowStateMapper workflowMapper;
    private final AgentStateMapper agentStateMapper;
    private final AgentArtifactMapper artifactMapper;
    private final WorkflowAgentStateService agentStateService;
    private final WorkflowReviewMapper reviewMapper;
    private final CustomerDataMapper customerDataMapper;
    private final ImportBatchMapper importBatchMapper;
    private final CurrentUserService currentUserService;
    private final IdempotencyExecutor idempotencyExecutor;
    private final WorkflowEventHub eventHub;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final FileStorageService fileStorageService;
    private final CustomerInsightAliasRestorer customerInsightAliasRestorer;

    @Transactional
    public WorkflowCreatedResponse create(
            CurrentUserPrincipal principal, String idempotencyKey, CreateWorkflowRequest request) {
        String key = principal.userId() + ":workflow:create:" + request.customerId() + ":"
                + request.importBatchId() + ":" + idempotencyKey;
        return idempotencyExecutor.execute(key, () -> createOnce(principal, request));
    }

    @Transactional(readOnly = true)
    public PageResponse<AvailableImportBatchResponse> availableImportBatches(
            CurrentUserPrincipal principal, Long customerId, int pageNo, int pageSize) {
        currentUserService.requireCustomerAccess(principal, customerId);
        if (customerDataMapper.findSummary(customerId) == null) {
            throw notFound("客户不存在");
        }
        long total = importBatchMapper.countAvailableForCustomer(customerId);
        List<AvailableImportBatchResponse> items = importBatchMapper.findAvailableForCustomer(
                customerId, (pageNo - 1) * pageSize, pageSize);
        return PageResponse.of(items, total, pageNo, pageSize);
    }

    @Transactional(readOnly = true)
    public PageResponse<CustomerManagerWorkflowResponse> customerManagerWorkflows(
            CurrentUserPrincipal principal,
            Long customerId,
            WorkflowStatus status,
            int pageNo,
            int pageSize) {
        if (customerId != null) {
            currentUserService.requireCustomerAccess(principal, customerId);
        }
        long total = workflowMapper.countForCustomerManager(principal.userId(), customerId, status);
        List<CustomerManagerWorkflowResponse> items = workflowMapper.findForCustomerManager(
                principal.userId(), customerId, status, (pageNo - 1) * pageSize, pageSize);
        return PageResponse.of(items, total, pageNo, pageSize);
    }

    @Transactional(readOnly = true)
    public PageResponse<CfsReportCenterItemResponse> reportCenter(
            CurrentUserPrincipal principal,
            Long customerId,
            String keyword,
            int pageNo,
            int pageSize) {
        if (customerId != null) {
            currentUserService.requireCustomerAccess(principal, customerId);
        }
        String normalizedKeyword = StringUtils.hasText(keyword) ? keyword.trim() : null;
        long total = workflowMapper.countForReportCenter(
                principal.userId(), customerId, normalizedKeyword);
        List<CfsReportCenterItemResponse> items = workflowMapper.findForReportCenter(
                        principal.userId(), customerId, normalizedKeyword,
                        (pageNo - 1) * pageSize, pageSize)
                .stream()
                .map(this::toReportCenterItem)
                .toList();
        return PageResponse.of(items, total, pageNo, pageSize);
    }

    @Transactional(readOnly = true)
    public CfsReportPreviewResponse reportPreview(
            CurrentUserPrincipal principal, String workflowId) {
        WorkflowState workflow = requireAccessible(principal, workflowId);
        ReportArtifacts artifacts = requireReportArtifacts(workflowId);
        requireReportCenterStatus(workflow);
        JsonNode content = reportContent(artifacts.cfs());
        List<CfsReportFileResponse> files = reportFiles(content);
        var customer = customerDataMapper.findSummary(workflow.getPersonId());
        String customerName = customer == null
                ? String.valueOf(workflow.getPersonId())
                : (StringUtils.hasText(customer.displayName()) ? customer.displayName() : customer.fullName());
        CfsReportStatus reportStatus = reportStatus(workflow);
        boolean canExport = workflow.getWorkflowStatus() == WorkflowStatus.COMPLETED && !files.isEmpty();
        return new CfsReportPreviewResponse(
                workflowId,
                workflow.getPersonId(),
                customerName,
                workflow.getWorkflowStatus(),
                reportStatus,
                artifacts.cfs().getArtifactId(),
                artifacts.cfs().getVersion(),
                artifacts.cfs().getCreateTime(),
                artifacts.compliance().getArtifactId(),
                artifacts.compliance().getComplianceResult(),
                artifacts.compliance().getCreateTime(),
                isPendingHumanReview(workflow.getWorkflowStatus()),
                canExport,
                files,
                text(content, "reportExportedAt"),
                previewContent(content));
    }

    private WorkflowCreatedResponse createOnce(CurrentUserPrincipal principal, CreateWorkflowRequest request) {
        currentUserService.requireCustomerAccess(principal, request.customerId());
        if (customerDataMapper.findSummary(request.customerId()) == null) {
            throw notFound("客户不存在");
        }
        if (!importBatchMapper.isCompletedAndAvailableForCustomer(
                request.importBatchId(), request.customerId())) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.INVALID_ARGUMENT,
                    "所选导入批次不可用、未完成或不包含该客户数据");
        }
        if (request.asOfDate().isAfter(java.time.LocalDate.now())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_ARGUMENT, "asOfDate不能晚于当前日期");
        }
        LocalDateTime now = LocalDateTime.now();
        WorkflowState workflow = new WorkflowState();
        workflow.setWorkflowId("WF-" + UUID.randomUUID());
        workflow.setPersonId(request.customerId());
        workflow.setImportBatchId(request.importBatchId());
        workflow.setCreatedBy(principal.userId());
        workflow.setAsOfDate(request.asOfDate());
        workflow.setTemplateId(request.templateId());
        workflow.setAnalysisRequirements(request.analysisRequirements());
        workflow.setWorkflowStatus(WorkflowStatus.CREATED);
        workflow.setVersion(0L);
        workflow.setCreatedAt(now);
        workflow.setUpdatedAt(now);
        workflowMapper.insert(workflow);

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
        states.forEach(agentStateMapper::insert);
        eventPublisher.publishEvent(new WorkflowCreatedEvent(workflow.getWorkflowId()));
        afterCommit(() -> eventHub.publish(workflow.getWorkflowId(), "WORKFLOW_CREATED",
                Map.of("workflowId", workflow.getWorkflowId(), "status", workflow.getWorkflowStatus())));
        return new WorkflowCreatedResponse(workflow.getWorkflowId(), workflow.getWorkflowStatus());
    }

    @Transactional(readOnly = true)
    public WorkflowDetailResponse detail(CurrentUserPrincipal principal, String workflowId) {
        WorkflowState workflow = requireAccessible(principal, workflowId);
        List<AgentStateResponse> states = agentStateMapper.selectList(Wrappers.<AgentState>lambdaQuery()
                        .eq(AgentState::getWorkflowId, workflowId)
                        .orderByAsc(AgentState::getAgentType))
                .stream().map(AgentStateResponse::from).toList();
        return WorkflowDetailResponse.from(workflow, states);
    }

    @Transactional(readOnly = true)
    public PageResponse<ArtifactRefResponse> artifacts(
            CurrentUserPrincipal principal, String workflowId, AgentType agentType, int pageNo, int pageSize) {
        requireAccessible(principal, workflowId);
        var query = Wrappers.<AgentArtifact>lambdaQuery()
                .eq(AgentArtifact::getWorkflowId, workflowId)
                .eq(agentType != null, AgentArtifact::getAgentType, agentType)
                .orderByDesc(AgentArtifact::getCreateTime);
        Page<AgentArtifact> page = artifactMapper.selectPage(new Page<>(pageNo, pageSize), query);
        return PageResponse.of(page.getRecords().stream().map(ArtifactRefResponse::from).toList(),
                page.getTotal(), pageNo, pageSize);
    }

    @Transactional(readOnly = true)
    public CustomerInsightAnalysisResponse customerInsight(CurrentUserPrincipal principal, String workflowId) {
        WorkflowState workflow = requireAccessible(principal, workflowId);
        AgentArtifact artifact = latestCustomerInsightArtifact(workflowId);
        if (artifact == null) {
            throw notFound("客户洞察结果尚未生成");
        }
        AgentState state = agentStateMapper.selectOne(Wrappers.<AgentState>lambdaQuery()
                .eq(AgentState::getWorkflowId, workflowId)
                .eq(AgentState::getAgentType, AgentType.CUSTOMER_INSIGHT));
        if (state == null) {
            throw notFound("客户洞察状态不存在");
        }
        return new CustomerInsightAnalysisResponse(
                workflowId,
                workflow.getWorkflowStatus(),
                state.getAgentStatus(),
                artifact.getArtifactId(),
                artifact.getExecutionId(),
                artifact.getVersion(),
                artifact.getCreateTime(),
                workflow.getWorkflowStatus() == WorkflowStatus.WAITING_INPUT
                        && state.getAgentStatus() == AgentStatus.SUCCESS,
                parseCustomerInsightAnalysis(artifact));
    }

    @Transactional
    public WorkflowDetailResponse retryCustomerInsight(
            CurrentUserPrincipal principal,
            String workflowId,
            String idempotencyKey,
            CustomerInsightRetryRequest request) {
        String key = principal.userId() + ":workflow:customer-insight-retry:" + workflowId + ":"
                + request.failedExecutionId() + ":" + idempotencyKey;
        return idempotencyExecutor.execute(key,
                () -> retryCustomerInsightOnce(principal, workflowId, request));
    }

    private WorkflowDetailResponse retryCustomerInsightOnce(
            CurrentUserPrincipal principal, String workflowId, CustomerInsightRetryRequest request) {
        WorkflowState workflow = requireAccessible(principal, workflowId);
        if (workflow.getWorkflowStatus() != WorkflowStatus.FAILED) {
            throw conflict("当前工作流不是失败状态，不能重试客户洞察");
        }
        if (!RETRYABLE_CUSTOMER_INSIGHT_FAILURES.contains(workflow.getErrorCode())) {
            throw conflict("当前工作流失败类型不支持重试客户洞察");
        }
        AgentState state = agentStateMapper.selectOne(Wrappers.<AgentState>lambdaQuery()
                .eq(AgentState::getWorkflowId, workflowId)
                .eq(AgentState::getAgentType, AgentType.CUSTOMER_INSIGHT));
        if (state == null) {
            throw notFound("客户洞察状态不存在");
        }
        if (state.getAgentStatus() != AgentStatus.FAILED) {
            throw conflict("客户洞察不是失败状态，不能重试");
        }
        if (!RETRYABLE_CUSTOMER_INSIGHT_FAILURES.contains(state.getErrorCode())) {
            throw conflict("当前客户洞察失败类型不支持重试");
        }
        if (!request.failedExecutionId().equals(state.getExecutionId())) {
            throw conflict("客户洞察失败执行已变化，请刷新状态后重试");
        }

        String failedExecutionId = state.getExecutionId();
        LocalDateTime now = LocalDateTime.now();
        agentStateService.ready(workflowId, AgentType.CUSTOMER_INSIGHT);

        workflow.setWorkflowStatus(WorkflowStatus.RUNNING);
        workflow.setErrorCode(null);
        workflow.setErrorMessage(null);
        workflow.setFinishTime(null);
        workflow.setUpdatedAt(now);
        updateWorkflow(workflow);

        eventPublisher.publishEvent(new KycRegenerationRequestedEvent(
                workflowId, request.description(), request.confirmedItems()));
        afterCommit(() -> eventHub.publish(workflowId, "KYC_RETRY_REQUESTED", Map.of(
                "workflowId", workflowId,
                "agentType", AgentType.CUSTOMER_INSIGHT,
                "failedExecutionId", failedExecutionId,
                "status", workflow.getWorkflowStatus())));
        return detail(principal, workflowId);
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
        AgentArtifact current = requireCurrentKycArtifact(workflowId, request.currentArtifactId());

        LocalDateTime now = LocalDateTime.now();
        if (request.action() == WorkflowInputRequest.Action.CONTINUE) {
            agentStateService.ready(workflowId, AgentType.MARKET_INSIGHT);
            agentStateService.ready(workflowId, AgentType.PRODUCT_EXPERT);
            workflow.setWorkflowStatus(WorkflowStatus.RUNNING);
            workflow.setErrorCode(null);
            workflow.setErrorMessage(null);
            if (workflow.getStartTime() == null) {
                workflow.setStartTime(now);
            }

            workflow.setUpdatedAt(now);
            updateWorkflow(workflow);
            List<AgentType> downstreamAgents = List.of(AgentType.MARKET_INSIGHT, AgentType.PRODUCT_EXPERT);
            eventPublisher.publishEvent(new DownstreamAgentsReadyEvent(
                    workflowId, current.getArtifactId(), downstreamAgents));
            afterCommit(() -> eventHub.publish(workflowId, "DOWNSTREAM_AGENTS_READY",
                    Map.of("workflowId", workflowId, "kycArtifactId", current.getArtifactId(),
                            "agentTypes", downstreamAgents, "status", workflow.getWorkflowStatus())));
        } else {
            requireSupplementForSupplementAction(request);
            List<KycQaItem> qaItems = mergeQaHistory(current, request.answers());
            agentStateService.ready(workflowId, AgentType.CUSTOMER_INSIGHT);
            workflow.setWorkflowStatus(WorkflowStatus.RUNNING);
            workflow.setUpdatedAt(now);
            updateWorkflow(workflow);
            eventPublisher.publishEvent(new KycRegenerationRequestedEvent(
                    workflowId, request.description(), request.confirmedItems(), qaItems));
            afterCommit(() -> eventHub.publish(workflowId, "KYC_REGENERATION_REQUESTED",
                    Map.of("workflowId", workflowId, "kycArtifactId", current.getArtifactId(),
                            "status", workflow.getWorkflowStatus())));
        }
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
        AgentArtifact cfs = requireArtifact(workflowId, request.cfsArtifactId(), AgentType.SOLUTION_DESIGN);
        AgentArtifact compliance = requireArtifact(
                workflowId, request.complianceArtifactId(), AgentType.COMPLIANCE_CHECK);
        if (!isHumanReviewableCompliance(compliance.getComplianceResult())) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    ErrorCode.COMPLIANCE_BLOCKED, "该合规结果不支持人工审核");
        }
        if (!isPendingHumanReview(workflow.getWorkflowStatus())) {
            throw conflict("当前工作流不允许审核");
        }
        verifyComplianceTargetsCfs(compliance, cfs);

        WorkflowReview latestReview = reviewMapper.selectOne(Wrappers.<WorkflowReview>lambdaQuery()
                .eq(WorkflowReview::getWorkflowId, workflowId)
                .orderByDesc(WorkflowReview::getReviewRound)
                .last("LIMIT 1"));
        int round = latestReview == null ? 1 : latestReview.getReviewRound() + 1;
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
        reviewMapper.insert(review);

        if (request.decision() == ReviewRequest.Decision.APPROVE) {
            workflow.setWorkflowStatus(WorkflowStatus.GENERATING_OUTPUT);
            workflow.setErrorCode(null);
            workflow.setErrorMessage(null);
            workflow.setFinishTime(null);
        } else {
            workflow.setWorkflowStatus(WorkflowStatus.RUNNING);
            agentStateService.ready(workflowId, AgentType.SOLUTION_DESIGN);
            afterCommit(() -> eventPublisher.publishEvent(new AgentDispatchRequestedEvent(
                    workflowId, AgentType.SOLUTION_DESIGN, latestInputRefs(workflowId))));
        }
        workflow.setUpdatedAt(LocalDateTime.now());
        updateWorkflow(workflow);
        if (request.decision() == ReviewRequest.Decision.APPROVE) {
            eventPublisher.publishEvent(new CfsReportExportRequestedEvent(
                    workflowId, cfs.getArtifactId(), compliance.getArtifactId()));
        }
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
        updateWorkflow(workflow);
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
            AgentArtifact cfs = latestArtifact(workflowId, AgentType.SOLUTION_DESIGN);
            AgentArtifact compliance = latestArtifact(workflowId, AgentType.COMPLIANCE_CHECK);
            if (cfs == null || compliance == null
                    || !isHumanReviewableCompliance(compliance.getComplianceResult())) {
                throw conflict("Output retry requires a reviewable CFS compliance result");
            }
            verifyComplianceTargetsCfs(compliance, cfs);
            workflow.setWorkflowStatus(WorkflowStatus.GENERATING_OUTPUT);
            workflow.setErrorCode(null);
            workflow.setErrorMessage(null);
            workflow.setFinishTime(null);
            workflow.setUpdatedAt(LocalDateTime.now());
            updateWorkflow(workflow);
            eventPublisher.publishEvent(new CfsReportExportRequestedEvent(
                    workflowId, cfs.getArtifactId(), compliance.getArtifactId()));
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
        AgentArtifact cfs = artifactMapper.selectOne(Wrappers.<AgentArtifact>lambdaQuery()
                .eq(AgentArtifact::getWorkflowId, workflowId)
                .eq(AgentArtifact::getAgentType, AgentType.SOLUTION_DESIGN)
                .orderByDesc(AgentArtifact::getVersion)
                .last("LIMIT 1"));
        if (cfs == null) {
            throw notFound("最终CFS不存在");
        }
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
        WorkflowState workflow = workflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw notFound("工作流不存在");
        }
        currentUserService.requireCustomerAccess(principal, workflow.getPersonId());
        return workflow;
    }

    private AgentArtifact requireArtifact(String workflowId, String artifactId, AgentType type) {
        AgentArtifact artifact = artifactMapper.selectById(artifactId);
        if (artifact == null) {
            throw notFound("Artifact不存在");
        }
        if (!workflowId.equals(artifact.getWorkflowId()) || artifact.getAgentType() != type) {
            throw new BusinessException(HttpStatus.CONFLICT, ErrorCode.STALE_ARTIFACT, "Artifact与当前工作流或阶段不匹配");
        }
        return artifact;
    }

    private AgentArtifact requireCurrentKycArtifact(String workflowId, String artifactId) {
        AgentArtifact current = requireArtifact(workflowId, artifactId, AgentType.CUSTOMER_INSIGHT);
        AgentArtifact latest = latestCustomerInsightArtifact(workflowId);
        if (latest == null || !current.getArtifactId().equals(latest.getArtifactId())) {
            throw new BusinessException(HttpStatus.CONFLICT, ErrorCode.STALE_ARTIFACT,
                    "当前KYC结果不是该工作流的最新版本，请刷新后重新审核");
        }
        return current;
    }

    private AgentArtifact latestCustomerInsightArtifact(String workflowId) {
        return latestArtifact(workflowId, AgentType.CUSTOMER_INSIGHT);
    }

    private AgentArtifact latestArtifact(String workflowId, AgentType type) {
        return artifactMapper.selectOne(Wrappers.<AgentArtifact>lambdaQuery()
                .eq(AgentArtifact::getWorkflowId, workflowId)
                .eq(AgentArtifact::getAgentType, type)
                .orderByDesc(AgentArtifact::getVersion)
                .last("LIMIT 1"));
    }

    private CustomerInsightAnalysisResponse.Analysis parseCustomerInsightAnalysis(AgentArtifact artifact) {
        if (!StringUtils.hasText(artifact.getResult())) {
            throw invalidCustomerInsightArtifact();
        }
        try {
            JsonNode result = objectMapper.readTree(artifact.getResult());
            JsonNode analysis = result.path("analysis");
            if (!"kyc-result.v2".equals(result.path("contractVersion").asText()) || !analysis.isObject()) {
                throw invalidCustomerInsightArtifact();
            }
            CustomerInsightAnalysisResponse.Analysis parsed = objectMapper.treeToValue(
                    analysis, CustomerInsightAnalysisResponse.Analysis.class);
            return customerInsightAliasRestorer.restore(
                    parsed, result.path("aliasMappings"), artifact.getArtifactId());
        } catch (JsonProcessingException exception) {
            throw invalidCustomerInsightArtifact();
        }
    }

    private BusinessException invalidCustomerInsightArtifact() {
        return new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
                "客户洞察结果格式无效");
    }

    private void requireSupplementForSupplementAction(WorkflowInputRequest request) {
        if (request.action() != WorkflowInputRequest.Action.SUPPLEMENT) {
            return;
        }
        boolean hasDescription = StringUtils.hasText(request.description());
        boolean hasConfirmedItem = request.confirmedItems() != null
                && request.confirmedItems().stream().anyMatch(StringUtils::hasText);
        boolean hasAnswer = request.answers() != null
                && request.answers().stream().anyMatch(answer -> answer != null && StringUtils.hasText(answer.answer()));
        if (!hasDescription && !hasConfirmedItem && !hasAnswer) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_ARGUMENT,
                    "补充KYC信息时必须提供补充说明、已确认事项或问题回答");
        }
    }

    private List<KycQaItem> mergeQaHistory(AgentArtifact current, List<WorkflowInputRequest.Answer> answers) {
        List<KycQaItem> merged = new ArrayList<>(extractQaHistory(current));
        if (answers == null || answers.isEmpty()) {
            return merged;
        }
        Map<String, CustomerInsightAnalysisResponse.FollowUpQuestion> questions = new java.util.LinkedHashMap<>();
        Set<String> submittedQuestionIds = new java.util.LinkedHashSet<>();
        for (CustomerInsightAnalysisResponse.FollowUpQuestion question : extractFollowUpQuestions(current)) {
            questions.put(question.id(), question);
        }
        for (WorkflowInputRequest.Answer answer : answers) {
            if (answer == null || !StringUtils.hasText(answer.answer())) {
                continue;
            }
            if (!submittedQuestionIds.add(answer.questionId())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_ARGUMENT,
                        "同一个问题不能重复提交回答: " + answer.questionId());
            }
            CustomerInsightAnalysisResponse.FollowUpQuestion question = questions.get(answer.questionId());
            if (question == null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_ARGUMENT,
                        "问题ID不存在于当前客户洞察分析: " + answer.questionId());
            }
            KycQaItem item = new KycQaItem(question.id(), question.question(), answer.answer());
            int existing = -1;
            for (int i = 0; i < merged.size(); i++) {
                if (question.id().equals(merged.get(i).questionId())) {
                    existing = i;
                    break;
                }
            }
            if (existing >= 0) {
                merged.set(existing, item);
            } else {
                merged.add(item);
            }
        }
        return List.copyOf(merged);
    }

    private List<CustomerInsightAnalysisResponse.FollowUpQuestion> extractFollowUpQuestions(AgentArtifact artifact) {
        if (!StringUtils.hasText(artifact.getResult())) {
            return List.of();
        }
        try {
            JsonNode analysis = objectMapper.readTree(artifact.getResult()).path("analysis");
            JsonNode questions = analysis.path("followUpQuestions");
            if (!questions.isArray()) {
                return List.of();
            }
            return objectMapper.convertValue(questions, new TypeReference<List<CustomerInsightAnalysisResponse.FollowUpQuestion>>() {});
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    private List<KycQaItem> extractQaHistory(AgentArtifact artifact) {
        if (!StringUtils.hasText(artifact.getResult())) {
            return List.of();
        }
        try {
            JsonNode history = objectMapper.readTree(artifact.getResult()).path("qaHistory");
            if (!history.isArray()) {
                return List.of();
            }
            List<KycQaItem> items = new ArrayList<>();
            for (JsonNode node : history) {
                items.add(new KycQaItem(
                        node.path("questionId").asText(null),
                        node.path("question").asText(null),
                        node.path("answer").asText(null)));
            }
            return items;
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    private void verifyComplianceTargetsCfs(AgentArtifact compliance, AgentArtifact cfs) {
        if (!StringUtils.hasText(compliance.getResult())) {
            throw new BusinessException(HttpStatus.CONFLICT, ErrorCode.STALE_ARTIFACT, "合规结果缺少CFS版本引用");
        }
        try {
            JsonNode result = objectMapper.readTree(compliance.getResult());
            String cfsArtifactId = result.path("cfsArtifactRef").asText(null);
            if (!StringUtils.hasText(cfsArtifactId)) {
                cfsArtifactId = result.path("cfsArtifactId").asText(null);
            }
            if (!cfs.getArtifactId().equals(cfsArtifactId)) {
                throw new BusinessException(HttpStatus.CONFLICT, ErrorCode.STALE_ARTIFACT, "合规结果与被审核CFS版本不一致");
            }
        } catch (JsonProcessingException exception) {
            throw new BusinessException(HttpStatus.CONFLICT, ErrorCode.STALE_ARTIFACT, "合规结果格式无效");
        }
    }

    private void updateWorkflow(WorkflowState workflow) {
        if (workflowMapper.updateById(workflow) != 1) {
            throw conflict("工作流已被其他请求更新，请刷新后重试");
        }
    }

    private CfsReportCenterItemResponse toReportCenterItem(CfsReportWorkflowRow row) {
        ReportArtifacts artifacts = reportArtifacts(row.workflowId());
        boolean validPair = validReportPair(artifacts);
        JsonNode content = validPair ? tryReportContent(artifacts.cfs()) : null;
        List<CfsReportFileResponse> files = content == null ? List.of() : reportFiles(content);
        CfsReportStatus reportStatus = reportStatus(row.workflowStatus(), row.errorCode());
        boolean canPreview = validPair && content != null;
        boolean canReview = canPreview && isPendingHumanReview(row.workflowStatus());
        boolean canExport = canPreview
                && row.workflowStatus() == WorkflowStatus.COMPLETED
                && !files.isEmpty();
        boolean canRetryExport = row.workflowStatus() == WorkflowStatus.GENERATING_OUTPUT
                && "CFS_REPORT_EXPORT_FAILED".equals(row.errorCode());
        return new CfsReportCenterItemResponse(
                row.workflowId(),
                row.customerId(),
                row.customerName(),
                row.workflowStatus(),
                reportStatus,
                row.templateId(),
                row.asOfDate(),
                artifacts.cfs() == null ? null : artifacts.cfs().getArtifactId(),
                artifacts.cfs() == null ? null : artifacts.cfs().getVersion(),
                artifacts.compliance() == null ? null : artifacts.compliance().getArtifactId(),
                artifacts.compliance() == null ? null : artifacts.compliance().getComplianceResult(),
                canPreview,
                canReview,
                canExport,
                canRetryExport,
                files,
                content == null ? null : text(content, "reportExportedAt"),
                row.errorCode(),
                row.errorMessage(),
                row.updatedAt());
    }

    private void requireReportCenterStatus(WorkflowState workflow) {
        if (!isPendingHumanReview(workflow.getWorkflowStatus())
                && workflow.getWorkflowStatus() != WorkflowStatus.GENERATING_OUTPUT
                && workflow.getWorkflowStatus() != WorkflowStatus.COMPLETED) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    ErrorCode.PRECONDITION_FAILED, "CFS尚未通过合规检查，不能预览正式报告");
        }
    }

    private CfsReportStatus reportStatus(WorkflowState workflow) {
        return reportStatus(workflow.getWorkflowStatus(), workflow.getErrorCode());
    }

    private CfsReportStatus reportStatus(WorkflowStatus workflowStatus, String errorCode) {
        return switch (workflowStatus) {
            case WAITING_INPUT, WAITING_REVIEW, FAILED -> CfsReportStatus.PENDING_REVIEW;
            case GENERATING_OUTPUT -> "CFS_REPORT_EXPORT_FAILED".equals(errorCode)
                    ? CfsReportStatus.EXPORT_FAILED
                    : CfsReportStatus.GENERATING;
            case COMPLETED -> CfsReportStatus.READY;
            default -> throw new IllegalArgumentException(
                    "Unsupported report center workflow status: " + workflowStatus);
        };
    }

    private ReportArtifacts reportArtifacts(String workflowId) {
        return new ReportArtifacts(
                latestArtifact(workflowId, AgentType.SOLUTION_DESIGN),
                latestArtifact(workflowId, AgentType.COMPLIANCE_CHECK));
    }

    private ReportArtifacts requireReportArtifacts(String workflowId) {
        ReportArtifacts artifacts = reportArtifacts(workflowId);
        if (artifacts.cfs() == null) {
            throw notFound("最终CFS不存在");
        }
        if (artifacts.compliance() == null) {
            throw notFound("合规检查结果不存在");
        }
        if (!isHumanReviewableCompliance(artifacts.compliance().getComplianceResult())) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    ErrorCode.COMPLIANCE_BLOCKED, "该合规结果不支持人工审核");
        }
        verifyComplianceTargetsCfs(artifacts.compliance(), artifacts.cfs());
        return artifacts;
    }

    private boolean validReportPair(ReportArtifacts artifacts) {
        if (artifacts.cfs() == null
                || artifacts.compliance() == null
                || !isHumanReviewableCompliance(artifacts.compliance().getComplianceResult())) {
            return false;
        }
        try {
            verifyComplianceTargetsCfs(artifacts.compliance(), artifacts.cfs());
            return true;
        } catch (BusinessException exception) {
            return false;
        }
    }
    private boolean isHumanReviewableCompliance(String complianceResult) {
        return "PASS".equalsIgnoreCase(complianceResult)
                || "REVIEW_REQUIRED".equalsIgnoreCase(complianceResult);
    }

    private boolean isPendingHumanReview(WorkflowStatus workflowStatus) {
        return workflowStatus == WorkflowStatus.WAITING_INPUT
                || workflowStatus == WorkflowStatus.WAITING_REVIEW
                || workflowStatus == WorkflowStatus.FAILED;
    }


    private JsonNode reportContent(AgentArtifact artifact) {
        if (artifact == null || !StringUtils.hasText(artifact.getResult())) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                    ErrorCode.INTERNAL_ERROR, "CFS报告内容为空");
        }
        try {
            JsonNode content = objectMapper.readTree(artifact.getResult());
            if (!content.isObject()) {
                throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                        ErrorCode.INTERNAL_ERROR, "CFS报告内容格式无效");
            }
            return content;
        } catch (JsonProcessingException exception) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                    ErrorCode.INTERNAL_ERROR, "CFS报告内容格式无效");
        }
    }

    private JsonNode tryReportContent(AgentArtifact artifact) {
        try {
            return reportContent(artifact);
        } catch (BusinessException exception) {
            return null;
        }
    }

    private JsonNode previewContent(JsonNode content) {
        ObjectNode preview = (ObjectNode) content.deepCopy();
        preview.remove("files");
        return preview;
    }

    private List<CfsReportFileResponse> reportFiles(JsonNode content) {
        JsonNode files = content.path("files");
        if (!files.isArray()) {
            return List.of();
        }
        List<CfsReportFileResponse> result = new ArrayList<>();
        for (JsonNode file : files) {
            if (!file.isObject() || !StringUtils.hasText(text(file, "fileId"))) {
                continue;
            }
            result.add(new CfsReportFileResponse(
                    text(file, "fileId"),
                    text(file, "format"),
                    text(file, "fileName"),
                    text(file, "contentType"),
                    file.has("sizeBytes") && file.path("sizeBytes").canConvertToLong()
                            ? file.path("sizeBytes").asLong()
                            : null,
                    text(file, "generatedAt")));
        }
        return List.copyOf(result);
    }

    private String text(JsonNode root, String field) {
        String value = root.path(field).asText(null);
        return StringUtils.hasText(value) ? value : null;
    }

    private record ReportArtifacts(AgentArtifact cfs, AgentArtifact compliance) {
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

    private Map<String, String> latestInputRefs(String workflowId) {
        AgentArtifact kyc = artifactMapper.selectOne(Wrappers.<AgentArtifact>lambdaQuery()
                .eq(AgentArtifact::getWorkflowId, workflowId)
                .eq(AgentArtifact::getAgentType, AgentType.CUSTOMER_INSIGHT)
                .orderByDesc(AgentArtifact::getVersion)
                .last("LIMIT 1"));
        AgentArtifact market = artifactMapper.selectOne(Wrappers.<AgentArtifact>lambdaQuery()
                .eq(AgentArtifact::getWorkflowId, workflowId)
                .eq(AgentArtifact::getAgentType, AgentType.MARKET_INSIGHT)
                .orderByDesc(AgentArtifact::getVersion)
                .last("LIMIT 1"));
        AgentArtifact kyp = artifactMapper.selectOne(Wrappers.<AgentArtifact>lambdaQuery()
                .eq(AgentArtifact::getWorkflowId, workflowId)
                .eq(AgentArtifact::getAgentType, AgentType.PRODUCT_EXPERT)
                .orderByDesc(AgentArtifact::getVersion)
                .last("LIMIT 1"));
        return Map.of(
                "kycArtifactId", kyc == null ? "下游Agent输入kycArtifact不存在" : kyc.getArtifactId(),
                "marketArtifactId", market == null ? "下游Agent输入竞争分析Artifact不存在" : market.getArtifactId(),
                "kypArtifactId", kyp == null ? "下游Agent输入kypArtifact不存在" : kyp.getArtifactId());
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
