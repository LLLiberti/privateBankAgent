package com.privatebank.agent.application.kyc;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KycAliasTextRestorerTest {

    private final KycAliasTextRestorer restorer = new KycAliasTextRestorer();

    @Test
    void restoresOnlyCompleteAliasTokensWithoutRecursiveReplacement() {
        Map<String, String> mappings = Map.of(
                "P-1", "E-1的实际控制人",
                "E-1", "某$科技\\集团");

        assertThat(restorer.restoreText(
                "客户P-1控制E-1，P-10和P-1A保持原样", mappings))
                .isEqualTo("客户E-1的实际控制人控制某$科技\\集团，P-10和P-1A保持原样");
    }

    @Test
    void restoresAliasesSplitAcrossSeveralDeltas() {
        KycAliasTextRestorer.StreamingRestorer streaming = restorer.streaming(Map.of(
                "P-1", "张三",
                "E-1", "某科技公司"));

        assertThat(streaming.accept("客户P-")).isEqualTo("客户");
        assertThat(streaming.accept("1控制E")).isEqualTo("张三控制");
        assertThat(streaming.accept("-1")).isEmpty();
        assertThat(streaming.accept("，需要关注风险")).isEqualTo("某科技公司，需要关注风险");
        assertThat(streaming.finish()).isEmpty();
    }

    @Test
    void waitsForRightBoundaryBeforeRestoringAnAlias() {
        KycAliasTextRestorer.StreamingRestorer streaming =
                restorer.streaming(Map.of("P-1", "张三"));

        assertThat(streaming.accept("P-1")).isEmpty();
        assertThat(streaming.accept("A与P-")).isEqualTo("P-1A与");
        assertThat(streaming.accept("1")).isEmpty();
        assertThat(streaming.finish()).isEqualTo("张三");
    }

    @Test
    void ignoresInvalidMappings() {
        assertThat(restorer.validateMappings(Map.of(
                "SRC-1", "证据",
                "P-1", "张三")))
                .containsExactly(Map.entry("P-1", "张三"));
    }
}
