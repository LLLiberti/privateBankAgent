package com.privatebank.business.service.workflow;

import com.privatebank.agent.application.kycchat.KycChatContext;
import com.privatebank.agent.application.kycchat.KycChatContextService;
import com.privatebank.agent.application.kycchat.KycChatSessionRegistry;
import com.privatebank.agent.application.kycchat.KycChatStreamingAgent;
import com.privatebank.agent.infrastructure.kyc.KycAsyncConfiguration;
import com.privatebank.business.dto.workflow.KycChatRequest;
import com.privatebank.business.security.CurrentUserPrincipal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

@Service
@Slf4j
public class KycChatService {

    private final KycChatContextService contextService;
    private final KycChatSessionRegistry sessionRegistry;
    private final KycChatStreamingAgent streamingAgent;
    private final Executor executor;

    public KycChatService(
            KycChatContextService contextService,
            KycChatSessionRegistry sessionRegistry,
            KycChatStreamingAgent streamingAgent,
            @Qualifier(KycAsyncConfiguration.KYC_EXECUTOR) Executor executor) {
        this.contextService = contextService;
        this.sessionRegistry = sessionRegistry;
        this.streamingAgent = streamingAgent;
        this.executor = executor;
    }

    public SseEmitter stream(
            CurrentUserPrincipal principal,
            String workflowId,
            String idempotencyKey,
            String lastEventId,
            KycChatRequest request) {
        KycChatContext context = contextService.requireContext(
                principal, workflowId, request.personId(), request.kycArtifactId());
        KycChatSessionRegistry.OpenResult open = sessionRegistry.open(
                context,
                principal.userId(),
                request.sessionId(),
                idempotencyKey,
                lastEventId,
                request.message(),
                mappings -> contextService.prepareTurn(context, request.message(), mappings));
        if (open.startsNewTurn()) {
            startAgent(open);
        }
        return open.emitter();
    }

    private void startAgent(KycChatSessionRegistry.OpenResult open) {
        try {
            executor.execute(() -> streamingAgent.stream(open.command()).subscribe(
                    delta -> sessionRegistry.delta(open.pointer(), delta),
                    error -> handleAgentFailure(open.pointer(), error),
                    () -> sessionRegistry.complete(open.pointer())));
        } catch (RejectedExecutionException exception) {
            sessionRegistry.fail(open.pointer(), "CHAT_CAPACITY_EXCEEDED",
                    "当前问答请求较多，请稍后重试", true);
        }
    }

    private void handleAgentFailure(
            KycChatSessionRegistry.TurnPointer pointer,
            Throwable error) {
        Throwable cause = rootCause(error);
        String code;
        String message;
        boolean retryable = true;
        if (cause instanceof TimeoutException) {
            code = "MODEL_TIMEOUT";
            message = "回答生成超时，请重试";
        } else if (cause instanceof IllegalStateException
                && "模型未生成可展示内容".equals(cause.getMessage())) {
            code = "EMPTY_RESPONSE";
            message = "模型未生成可展示内容，请重试";
        } else {
            code = "MODEL_UNAVAILABLE";
            message = "问答模型暂时不可用，请稍后重试";
        }
        log.warn("KYC chat generation failed: code={}, rootCauseType={}",
                code, cause.getClass().getSimpleName());
        sessionRegistry.fail(pointer, code, message, retryable);
    }

    private Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
