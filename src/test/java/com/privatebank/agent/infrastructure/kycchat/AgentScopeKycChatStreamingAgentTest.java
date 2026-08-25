package com.privatebank.agent.infrastructure.kycchat;

import io.agentscope.core.tool.Tool;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentScopeKycChatStreamingAgentTest {

    @Test
    void boundToolExposesNoIdentityParametersAndReturnsOnlyPreparedMaskedData() throws Exception {
        AgentScopeKycChatStreamingAgent.BoundCustomerDataTool tool =
                new AgentScopeKycChatStreamingAgent.BoundCustomerDataTool(
                        "SAME_AS_KYC_INPUT",
                        Map.of("person", Map.of("personAlias", "P-1")));
        Method method = tool.getClass().getMethod("readCurrentPersonMaskedData");
        Tool annotation = method.getAnnotation(Tool.class);

        assertThat(method.getParameterCount()).isZero();
        assertThat(annotation).isNotNull();
        assertThat(annotation.name()).isEqualTo("read_current_person_masked_data");
        assertThat(annotation.readOnly()).isTrue();
        assertThat(tool.readCurrentPersonMaskedData())
                .containsEntry("snapshotComparison", "SAME_AS_KYC_INPUT")
                .containsKey("maskedCustomerData")
                .doesNotContainKeys("personId", "workflowId", "kycArtifactId");
    }
}
