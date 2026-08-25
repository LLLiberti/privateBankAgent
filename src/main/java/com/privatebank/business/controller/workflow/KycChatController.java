package com.privatebank.business.controller.workflow;

import com.privatebank.business.dto.workflow.KycChatRequest;
import com.privatebank.business.security.CurrentUserPrincipal;
import com.privatebank.business.service.workflow.KycChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/cfs/workflows")
@RequiredArgsConstructor
public class KycChatController {

    private final KycChatService chatService;

    @PostMapping(path = "/{workflowId}/kyc-chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasRole('CUSTOMER_MANAGER')")
    public ResponseEntity<SseEmitter> stream(
            @AuthenticationPrincipal CurrentUserPrincipal principal,
            @PathVariable String workflowId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            @Valid @RequestBody KycChatRequest request) {
        SseEmitter emitter = chatService.stream(
                principal, workflowId, idempotencyKey, lastEventId, request);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store")
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }
}
