package com.privatebank.business.controller.workflow;

import com.privatebank.business.dto.workflow.ArtifactRefResponse;
import com.privatebank.business.dto.workflow.CancelRequest;
import com.privatebank.business.dto.workflow.CreateWorkflowRequest;
import com.privatebank.business.dto.workflow.OutputRetryRequest;
import com.privatebank.business.dto.workflow.OutputStatusResponse;
import com.privatebank.business.dto.workflow.ReviewRequest;
import com.privatebank.business.dto.workflow.ReviewResponse;
import com.privatebank.business.dto.workflow.WorkflowCreatedResponse;
import com.privatebank.business.dto.workflow.WorkflowDetailResponse;
import com.privatebank.business.dto.workflow.WorkflowInputRequest;
import com.privatebank.business.dto.workflow.WorkflowResultResponse;
import com.privatebank.business.dto.common.PageResponse;
import com.privatebank.business.security.CurrentUserPrincipal;
import com.privatebank.business.service.workflow.WorkflowService;
import com.privatebank.business.enums.workflow.AgentType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;

@Validated
@RestController
@RequestMapping("/api/cfs/workflows")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowService workflowService;

    @PostMapping
    @PreAuthorize("hasRole('CUSTOMER_MANAGER')")
    public WorkflowCreatedResponse create(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateWorkflowRequest request) {
        return workflowService.create(principal, idempotencyKey, request);
    }

    @GetMapping("/{workflowId}")
    public WorkflowDetailResponse detail(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @PathVariable String workflowId) {
        return workflowService.detail(principal, workflowId);
    }

    @GetMapping(path = "/{workflowId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @PathVariable String workflowId) {
        return workflowService.subscribe(principal, workflowId);
    }

    @GetMapping("/{workflowId}/artifacts")
    public PageResponse<ArtifactRefResponse> artifacts(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @PathVariable String workflowId,
            @RequestParam(required = false) AgentType agentType,
            @RequestParam(defaultValue = "1") @Min(1) int pageNo,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return workflowService.artifacts(principal, workflowId, agentType, pageNo, pageSize);
    }

    @PostMapping("/{workflowId}/inputs")
    @PreAuthorize("hasRole('CUSTOMER_MANAGER')")
    public WorkflowDetailResponse input(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @PathVariable String workflowId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody WorkflowInputRequest request) {
        return workflowService.provideInput(principal, workflowId, idempotencyKey, request);
    }

    @PostMapping("/{workflowId}/reviews")
    @PreAuthorize("hasRole('CUSTOMER_MANAGER')")
    public ReviewResponse review(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @PathVariable String workflowId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ReviewRequest request) {
        return workflowService.review(principal, workflowId, idempotencyKey, request);
    }

    @GetMapping("/{workflowId}/result")
    public WorkflowResultResponse result(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @PathVariable String workflowId) {
        return workflowService.result(principal, workflowId);
    }

    @PostMapping("/{workflowId}/cancel")
    @PreAuthorize("hasRole('CUSTOMER_MANAGER')")
    public WorkflowCreatedResponse cancel(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @PathVariable String workflowId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CancelRequest request) {
        return workflowService.cancel(principal, workflowId, idempotencyKey, request);
    }

    @PostMapping("/{workflowId}/outputs/retry")
    @PreAuthorize("hasRole('CUSTOMER_MANAGER')")
    public OutputStatusResponse retryOutput(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @PathVariable String workflowId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody OutputRetryRequest request) {
        return workflowService.retryOutput(principal, workflowId, idempotencyKey, request);
    }

    @GetMapping("/{workflowId}/files/{fileId}")
    public ResponseEntity<org.springframework.core.io.Resource> download(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @PathVariable String workflowId,
            @PathVariable String fileId) {
        WorkflowService.DownloadFile file = workflowService.download(principal, workflowId, fileId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(file.resource());
    }
}
