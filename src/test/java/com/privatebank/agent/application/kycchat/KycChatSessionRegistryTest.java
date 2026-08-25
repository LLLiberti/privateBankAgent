package com.privatebank.agent.application.kycchat;

import com.privatebank.business.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KycChatSessionRegistryTest {

    private final KycChatSessionRegistry registry = new KycChatSessionRegistry(30, 150, 8000);
    private final KycChatContext context = new KycChatContext(
            "WF-1",
            1001L,
            "ART-KYC-1",
            "{\"summary\":\"P-1的KYC结论\"}",
            "hash",
            Map.of("P-1", "张三"));

    @Test
    void reconnectsSameTurnAndRejectsIdempotencyKeyReuseForAnotherMessage() {
        KycChatSessionRegistry.OpenResult first = open(null, "IDEMP-1", "请解释结论");

        assertThat(first.startsNewTurn()).isTrue();
        assertThat(first.command().sessionId()).startsWith("CHAT-");

        KycChatSessionRegistry.OpenResult reconnect = open(null, "IDEMP-1", "请解释结论");
        assertThat(reconnect.startsNewTurn()).isFalse();
        assertThat(reconnect.pointer()).isSameAs(first.pointer());

        assertThatThrownBy(() -> open(null, "IDEMP-1", "另一条消息"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("相同Idempotency-Key");
    }

    @Test
    void acceptsLastEventIdForSameTurnAndRejectsAnotherTurn() {
        KycChatSessionRegistry.OpenResult first = open(null, "IDEMP-1", "请解释结论");
        registry.delta(first.pointer(), "第一段");
        registry.delta(first.pointer(), "第二段");

        KycChatSessionRegistry.OpenResult reconnect = open(
                null, "IDEMP-1", first.command().turnId() + ":2", "请解释结论");
        assertThat(reconnect.startsNewTurn()).isFalse();

        assertThatThrownBy(() -> open(
                null, "IDEMP-1", "TURN-OTHER:2", "请解释结论"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Last-Event-ID");
    }

    @Test
    void allowsOnlyOneActiveTurnAndRetainsOnlyCompletedHistory() {
        KycChatSessionRegistry.OpenResult first = open(null, "IDEMP-1", "请解释结论");
        String sessionId = first.command().sessionId();

        assertThatThrownBy(() -> open(sessionId, "IDEMP-2", "继续说明"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已有回答正在生成");

        registry.delta(first.pointer(), "这是回答");
        registry.complete(first.pointer());

        KycChatSessionRegistry.OpenResult second = open(sessionId, "IDEMP-2", "继续说明");
        assertThat(second.command().history())
                .extracting(KycChatMessage::content)
                .containsExactly("请解释结论", "这是回答");
    }

    @Test
    void rejectsSessionReuseForAnotherKycBinding() {
        KycChatSessionRegistry.OpenResult first = open(null, "IDEMP-1", "请解释结论");
        registry.delta(first.pointer(), "回答");
        registry.complete(first.pointer());
        KycChatContext anotherArtifact = new KycChatContext(
                "WF-1", 1001L, "ART-KYC-2", "{}", "hash2", Map.of("P-1", "张三"));

        assertThatThrownBy(() -> registry.open(
                anotherArtifact,
                "USER-1",
                first.command().sessionId(),
                "IDEMP-2",
                null,
                "新问题",
                mappings -> prepared("新问题")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不匹配");
    }

    private KycChatSessionRegistry.OpenResult open(
            String sessionId, String idempotencyKey, String message) {
        return open(sessionId, idempotencyKey, null, message);
    }

    private KycChatSessionRegistry.OpenResult open(
            String sessionId,
            String idempotencyKey,
            String lastEventId,
            String message) {
        return registry.open(
                context,
                "USER-1",
                sessionId,
                idempotencyKey,
                lastEventId,
                message,
                mappings -> prepared(message));
    }

    private KycChatPreparedTurn prepared(String message) {
        return new KycChatPreparedTurn(
                message,
                Map.of("person", Map.of("personAlias", "P-1")),
                KycChatContextService.SAME_AS_KYC_INPUT,
                Map.of("P-1", "张三"));
    }
}
