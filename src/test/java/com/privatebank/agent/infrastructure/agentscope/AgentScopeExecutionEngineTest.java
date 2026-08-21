package com.privatebank.agent.infrastructure.agentscope;

import com.privatebank.agent.application.runtime.AgentRuntimeException;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentScopeExecutionEngineTest {

    @Test
    void acceptsStructuredDataWhenGenerateReasonIsMissing() {
        Msg result = structuredResult(null);

        assertThatCode(() -> AgentScopeExecutionEngine.validateStructuredResult(result))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsExplicitFailureReasonEvenWhenStructuredDataExists() {
        Msg result = structuredResult(GenerateReason.MAX_ITERATIONS);

        assertThatThrownBy(() -> AgentScopeExecutionEngine.validateStructuredResult(result))
                .isInstanceOf(AgentRuntimeException.class)
                .hasMessageContaining("MAX_ITERATIONS");
    }

    @Test
    void rejectsResultWithoutStructuredData() {
        Msg result = Msg.builderForRole(MsgRole.ASSISTANT)
                .textContent("{}")
                .generateReason(GenerateReason.MODEL_STOP)
                .build();

        assertThatThrownBy(() -> AgentScopeExecutionEngine.validateStructuredResult(result))
                .isInstanceOf(AgentRuntimeException.class)
                .hasMessageContaining("未返回结构化结果");
    }

    private Msg structuredResult(GenerateReason generateReason) {
        Msg.Builder builder = Msg.builderForRole(MsgRole.ASSISTANT)
                .textContent("{}")
                .metadata(Map.of("_structured_output", Map.of("value", "ok")));
        if (generateReason != null) {
            builder.generateReason(generateReason);
        }
        return builder.build();
    }
}
