package com.privatebank.agent.application.kycchat;

import com.privatebank.business.common.exception.BusinessException;
import com.privatebank.business.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Single-instance, runtime-only storage for chat memory, idempotency and SSE event replay. */
@Component
public class KycChatSessionRegistry {

    private static final int MAX_HISTORY_MESSAGES = 40;

    private final Map<String, ChatSession> sessions = new ConcurrentHashMap<>();
    private final Map<TurnKey, TurnPointer> turnsByKey = new ConcurrentHashMap<>();
    private final Map<TurnKey, Object> idempotencyLocks = new ConcurrentHashMap<>();
    private final Duration sessionTtl;
    private final long emitterTimeoutMillis;
    private final int maxAnswerCodePoints;

    public KycChatSessionRegistry(
            @Value("${private-bank.kyc-chat.session-ttl-minutes:30}") long sessionTtlMinutes,
            @Value("${private-bank.kyc-chat.emitter-timeout-seconds:150}") long emitterTimeoutSeconds,
            @Value("${private-bank.kyc-chat.max-answer-code-points:8000}") int maxAnswerCodePoints) {
        this.sessionTtl = Duration.ofMinutes(sessionTtlMinutes);
        this.emitterTimeoutMillis = Duration.ofSeconds(emitterTimeoutSeconds).toMillis();
        this.maxAnswerCodePoints = maxAnswerCodePoints;
    }

    public OpenResult open(
            KycChatContext context,
            String userId,
            String requestedSessionId,
            String idempotencyKey,
            String lastEventId,
            String rawMessage,
            Function<Map<String, String>, KycChatPreparedTurn> preparer) {
        validateIdempotencyKey(idempotencyKey);
        evictExpired();
        TurnKey turnKey = new TurnKey(userId, context.workflowId(), idempotencyKey);
        Object lock = idempotencyLocks.computeIfAbsent(turnKey, ignored -> new Object());
        synchronized (lock) {
            try {
                return openLocked(
                        context,
                        userId,
                        requestedSessionId,
                        turnKey,
                        lastEventId,
                        rawMessage,
                        preparer);
            } finally {
                if (!turnsByKey.containsKey(turnKey)) {
                    idempotencyLocks.remove(turnKey, lock);
                }
            }
        }
    }

    private OpenResult openLocked(
            KycChatContext context,
            String userId,
            String requestedSessionId,
            TurnKey turnKey,
            String lastEventId,
            String rawMessage,
            Function<Map<String, String>, KycChatPreparedTurn> preparer) {
        TurnPointer existing = turnsByKey.get(turnKey);
        if (existing != null) {
            validateReconnect(existing, context, requestedSessionId, rawMessage);
            return new OpenResult(attach(existing.turn(), lastEventId), null, existing);
        }

        ChatSession session = resolveSession(context, userId, requestedSessionId);
        synchronized (session) {
            if (session.activeTurn != null && !session.activeTurn.terminal()) {
                throw business(HttpStatus.CONFLICT, ErrorCode.STATE_CONFLICT,
                        "当前会话已有回答正在生成");
            }
            KycChatPreparedTurn prepared = preparer.apply(Map.copyOf(session.aliasMappings));
            session.aliasMappings.clear();
            session.aliasMappings.putAll(prepared.aliasMappings());
            session.lastAccessAt = Instant.now();

            String turnId = "TURN-" + UUID.randomUUID();
            ChatTurn turn = new ChatTurn(turnId, fingerprint(rawMessage), prepared.maskedMessage());
            session.turns.add(turn);
            session.activeTurn = turn;
            TurnPointer pointer = new TurnPointer(session, turn, turnKey);
            turnsByKey.put(turnKey, pointer);

            KycChatAgentCommand command = new KycChatAgentCommand(
                    session.sessionId,
                    turnId,
                    userId,
                    context.workflowId(),
                    context.personId(),
                    context.kycArtifactId(),
                    context.kycAnalysisJson(),
                    List.copyOf(session.history),
                    prepared.maskedMessage(),
                    prepared.currentMaskedData(),
                    prepared.snapshotComparison());
            SseEmitter emitter = attach(turn, null);
            publish(turn, "session", mapOf(
                    "sessionId", session.sessionId,
                    "turnId", turnId,
                    "workflowId", context.workflowId(),
                    "personId", context.personId(),
                    "kycArtifactId", context.kycArtifactId(),
                    "aliasMappings", Map.copyOf(session.aliasMappings),
                    "expiresInSeconds", sessionTtl.toSeconds()));
            return new OpenResult(emitter, command, pointer);
        }
    }

    public void delta(TurnPointer pointer, String delta) {
        if (!StringUtils.hasLength(delta)) {
            return;
        }
        ChatTurn turn = pointer.turn();
        String accepted;
        synchronized (turn) {
            if (turn.terminal() || turn.truncated) {
                return;
            }
            int current = turn.answer.codePointCount(0, turn.answer.length());
            int remaining = maxAnswerCodePoints - current;
            if (remaining <= 0) {
                turn.truncated = true;
                return;
            }
            int deltaLength = delta.codePointCount(0, delta.length());
            if (deltaLength > remaining) {
                accepted = delta.substring(0, delta.offsetByCodePoints(0, remaining));
                turn.truncated = true;
            } else {
                accepted = delta;
            }
            turn.answer.append(accepted);
        }
        if (StringUtils.hasLength(accepted)) {
            publish(turn, "delta", mapOf("turnId", turn.turnId, "content", accepted));
        }
    }

    public void complete(TurnPointer pointer) {
        ChatSession session = pointer.session();
        ChatTurn turn = pointer.turn();
        String answer;
        String finishReason;
        synchronized (session) {
            synchronized (turn) {
                if (turn.terminal()) {
                    return;
                }
                answer = turn.answer.toString();
                if (!StringUtils.hasText(answer)) {
                    turn.status = TurnStatus.FAILED;
                    finishReason = null;
                } else {
                    turn.status = TurnStatus.COMPLETED;
                    finishReason = turn.truncated ? "length" : "stop";
                    session.history.add(new KycChatMessage(KycChatMessage.Role.USER, turn.maskedMessage));
                    session.history.add(new KycChatMessage(KycChatMessage.Role.ASSISTANT, answer));
                    trimHistory(session.history);
                }
                if (session.activeTurn == turn) {
                    session.activeTurn = null;
                }
                session.lastAccessAt = Instant.now();
            }
        }
        if (finishReason == null) {
            fail(pointer, "EMPTY_RESPONSE", "模型未生成可展示内容", true);
            return;
        }
        publish(turn, "done", mapOf("turnId", turn.turnId, "finishReason", finishReason));
        completeEmitter(turn);
    }

    public void fail(TurnPointer pointer, String code, String message, boolean retryable) {
        ChatSession session = pointer.session();
        ChatTurn turn = pointer.turn();
        synchronized (session) {
            synchronized (turn) {
                if (turn.status == TurnStatus.COMPLETED || turn.errorPublished) {
                    return;
                }
                turn.status = TurnStatus.FAILED;
                turn.errorPublished = true;
                if (session.activeTurn == turn) {
                    session.activeTurn = null;
                }
                session.lastAccessAt = Instant.now();
            }
        }
        publish(turn, "error", mapOf(
                "turnId", turn.turnId,
                "code", code,
                "message", message,
                "retryable", retryable));
        completeEmitter(turn);
    }

    private ChatSession resolveSession(
            KycChatContext context,
            String userId,
            String requestedSessionId) {
        if (!StringUtils.hasText(requestedSessionId)) {
            ChatSession session = new ChatSession(
                    "CHAT-" + UUID.randomUUID(), userId, context, context.aliasMappings());
            sessions.put(session.sessionId, session);
            return session;
        }
        ChatSession session = sessions.get(requestedSessionId);
        if (session == null || expired(session)) {
            throw business(HttpStatus.GONE, ErrorCode.STATE_CONFLICT,
                    "Chat会话不存在或已过期，请新建会话");
        }
        validateSession(session, context, userId);
        return session;
    }

    private void validateReconnect(
            TurnPointer pointer,
            KycChatContext context,
            String requestedSessionId,
            String rawMessage) {
        validateSession(pointer.session(), context, pointer.turnKey().userId());
        if (StringUtils.hasText(requestedSessionId)
                && !requestedSessionId.equals(pointer.session().sessionId)) {
            throw business(HttpStatus.CONFLICT, ErrorCode.STATE_CONFLICT,
                    "幂等键属于另一个Chat会话");
        }
        if (!pointer.turn().messageFingerprint.equals(fingerprint(rawMessage))) {
            throw business(HttpStatus.CONFLICT, ErrorCode.STATE_CONFLICT,
                    "相同Idempotency-Key不能用于不同消息");
        }
        pointer.session().lastAccessAt = Instant.now();
    }

    private void validateSession(ChatSession session, KycChatContext context, String userId) {
        if (!session.userId.equals(userId)
                || !session.workflowId.equals(context.workflowId())
                || !session.personId.equals(context.personId())
                || !session.kycArtifactId.equals(context.kycArtifactId())) {
            throw business(HttpStatus.CONFLICT, ErrorCode.STATE_CONFLICT,
                    "Chat会话与当前用户、工作流、人员或KYC结果不匹配");
        }
    }

    private SseEmitter attach(ChatTurn turn, String lastEventId) {
        long afterSequence = parseLastEventId(turn.turnId, lastEventId);
        SseEmitter emitter = new SseEmitter(emitterTimeoutMillis);
        emitter.onCompletion(() -> detach(turn, emitter));
        emitter.onTimeout(() -> detach(turn, emitter));
        emitter.onError(error -> detach(turn, emitter));
        synchronized (turn) {
            SseEmitter previous = turn.emitter;
            turn.emitter = emitter;
            if (previous != null && previous != emitter) {
                previous.complete();
            }
            for (ChatEvent event : turn.events) {
                if (event.sequence() > afterSequence && !send(emitter, event)) {
                    turn.emitter = null;
                    return emitter;
                }
            }
            if (turn.terminal()) {
                emitter.complete();
            }
        }
        return emitter;
    }

    private void publish(ChatTurn turn, String name, Map<String, Object> data) {
        synchronized (turn) {
            ChatEvent event = new ChatEvent(++turn.nextSequence, name, data);
            turn.events.add(event);
            if (turn.emitter != null && !send(turn.emitter, event)) {
                turn.emitter = null;
            }
        }
    }

    private boolean send(SseEmitter emitter, ChatEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .id(eventId(event))
                    .name(event.name())
                    .data(event.data()));
            return true;
        } catch (IOException | IllegalStateException exception) {
            return false;
        }
    }

    private String eventId(ChatEvent event) {
        Object turnId = event.data().get("turnId");
        return turnId + ":" + event.sequence();
    }

    private long parseLastEventId(String turnId, String lastEventId) {
        if (!StringUtils.hasText(lastEventId)) {
            return 0L;
        }
        int separator = lastEventId.lastIndexOf(':');
        if (separator <= 0 || !turnId.equals(lastEventId.substring(0, separator))) {
            throw business(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_ARGUMENT,
                    "Last-Event-ID与当前消息轮次不匹配");
        }
        try {
            long sequence = Long.parseLong(lastEventId.substring(separator + 1));
            if (sequence < 0) {
                throw new NumberFormatException();
            }
            return sequence;
        } catch (NumberFormatException exception) {
            throw business(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_ARGUMENT,
                    "Last-Event-ID格式无效");
        }
    }

    private void completeEmitter(ChatTurn turn) {
        synchronized (turn) {
            if (turn.emitter != null) {
                SseEmitter emitter = turn.emitter;
                turn.emitter = null;
                emitter.complete();
            }
        }
    }

    private void detach(ChatTurn turn, SseEmitter emitter) {
        synchronized (turn) {
            if (turn.emitter == emitter) {
                turn.emitter = null;
            }
        }
    }

    private void trimHistory(List<KycChatMessage> history) {
        while (history.size() > MAX_HISTORY_MESSAGES) {
            history.remove(0);
        }
    }

    private void evictExpired() {
        List<ChatSession> expiredSessions = sessions.values().stream()
                .filter(this::expired)
                .filter(session -> session.activeTurn == null || session.activeTurn.terminal())
                .toList();
        for (ChatSession session : expiredSessions) {
            if (sessions.remove(session.sessionId, session)) {
                for (ChatTurn turn : session.turns) {
                    turnsByKey.entrySet().removeIf(entry -> {
                        boolean matches = entry.getValue().turn() == turn;
                        if (matches) {
                            idempotencyLocks.remove(entry.getKey());
                        }
                        return matches;
                    });
                }
            }
        }
    }

    private boolean expired(ChatSession session) {
        return session.lastAccessAt.isBefore(Instant.now().minus(sessionTtl));
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey) || idempotencyKey.length() > 256) {
            throw business(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_ARGUMENT,
                    "Idempotency-Key不能为空且不能超过256字符");
        }
    }

    private String fingerprint(String message) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(message.trim().getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("无法生成消息校验值", exception);
        }
    }

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }

    private BusinessException business(HttpStatus status, ErrorCode code, String message) {
        return new BusinessException(status, code, message);
    }

    public record OpenResult(
            SseEmitter emitter,
            KycChatAgentCommand command,
            TurnPointer pointer) {

        public boolean startsNewTurn() {
            return command != null;
        }
    }

    public record TurnPointer(ChatSession session, ChatTurn turn, TurnKey turnKey) {
    }

    private record TurnKey(String userId, String workflowId, String idempotencyKey) {
    }

    private record ChatEvent(long sequence, String name, Map<String, Object> data) {
    }

    public static final class ChatSession {
        private final String sessionId;
        private final String userId;
        private final String workflowId;
        private final Long personId;
        private final String kycArtifactId;
        private final Map<String, String> aliasMappings = new LinkedHashMap<>();
        private final List<KycChatMessage> history = new ArrayList<>();
        private final List<ChatTurn> turns = new ArrayList<>();
        private volatile Instant lastAccessAt = Instant.now();
        private ChatTurn activeTurn;

        private ChatSession(
                String sessionId,
                String userId,
                KycChatContext context,
                Map<String, String> aliasMappings) {
            this.sessionId = sessionId;
            this.userId = userId;
            this.workflowId = context.workflowId();
            this.personId = context.personId();
            this.kycArtifactId = context.kycArtifactId();
            this.aliasMappings.putAll(aliasMappings);
        }
    }

    public static final class ChatTurn {
        private final String turnId;
        private final String messageFingerprint;
        private final String maskedMessage;
        private final StringBuilder answer = new StringBuilder();
        private final List<ChatEvent> events = new ArrayList<>();
        private volatile TurnStatus status = TurnStatus.STREAMING;
        private long nextSequence;
        private boolean truncated;
        private boolean errorPublished;
        private SseEmitter emitter;

        private ChatTurn(String turnId, String messageFingerprint, String maskedMessage) {
            this.turnId = turnId;
            this.messageFingerprint = messageFingerprint;
            this.maskedMessage = maskedMessage;
        }

        private boolean terminal() {
            return status == TurnStatus.COMPLETED || status == TurnStatus.FAILED;
        }
    }

    private enum TurnStatus {
        STREAMING,
        COMPLETED,
        FAILED
    }
}
