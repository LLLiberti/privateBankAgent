package com.privatebank.agent.application.kycchat;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KycChatAliasNormalizerTest {

    private final KycChatAliasNormalizer normalizer = new KycChatAliasNormalizer();

    @Test
    void keepsPersistedAliasesAndAllocatesNonConflictingAliasesForNewEntities() {
        KycChatAliasNormalizer.AliasPlan plan = normalizer.plan(
                Map.of(
                        "E-1", "新增企业",
                        "E-2", "原有企业",
                        "P-1", "张三"),
                Map.of(
                        "E-1", "原有企业",
                        "P-1", "张三"));

        assertThat(plan.replacements())
                .containsEntry("E-2", "E-1")
                .containsEntry("E-1", "E-2")
                .containsEntry("P-1", "P-1");
        assertThat(plan.canonicalMappings())
                .containsEntry("E-1", "原有企业")
                .containsEntry("E-2", "新增企业");
    }

    @Test
    void replacesAliasesInNestedPayloadWithoutSequentialReplacementCollision() {
        KycChatAliasNormalizer.AliasPlan plan = normalizer.plan(
                Map.of("E-1", "新增企业", "E-2", "原有企业"),
                Map.of("E-1", "原有企业"));

        Map<String, Object> normalized = normalizer.normalizePayload(
                Map.of("text", "E-1与E-2存在关系", "items", List.of(Map.of("alias", "E-2"))),
                plan);

        assertThat(normalized.get("text")).isEqualTo("E-2与E-1存在关系");
        assertThat(normalized.toString()).contains("alias=E-1");
    }

    @Test
    void doesNotMergeSameRawValueAcrossAliasCategories() {
        KycChatAliasNormalizer.AliasPlan plan = normalizer.plan(
                Map.of("P-1", "同名主体", "E-1", "同名主体"),
                Map.of("P-1", "同名主体"));

        assertThat(plan.replacements())
                .containsEntry("P-1", "P-1")
                .containsEntry("E-1", "E-1");
        assertThat(plan.canonicalMappings())
                .containsEntry("P-1", "同名主体")
                .containsEntry("E-1", "同名主体");
    }
}
