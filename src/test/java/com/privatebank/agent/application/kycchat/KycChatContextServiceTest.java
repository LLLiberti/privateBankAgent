package com.privatebank.agent.application.kycchat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.agent.application.kyc.KycDataMaskingService;
import com.privatebank.agent.application.kyc.KycRuntimeSupplement;
import com.privatebank.agent.domain.kyc.KycCustomerData;
import com.privatebank.agent.domain.kyc.KycMaskedInput;
import com.privatebank.agent.infrastructure.kyc.KycCustomerDataLoader;
import com.privatebank.business.common.exception.BusinessException;
import com.privatebank.business.entity.workflow.AgentArtifact;
import com.privatebank.business.entity.workflow.WorkflowState;
import com.privatebank.business.enums.auth.RoleName;
import com.privatebank.business.enums.workflow.AgentType;
import com.privatebank.business.mapper.workflow.AgentArtifactMapper;
import com.privatebank.business.mapper.workflow.WorkflowStateMapper;
import com.privatebank.business.security.CurrentUserPrincipal;
import com.privatebank.business.security.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KycChatContextServiceTest {

    @Mock WorkflowStateMapper workflowStateMapper;
    @Mock AgentArtifactMapper artifactMapper;
    @Mock CurrentUserService currentUserService;
    @Mock KycCustomerDataLoader customerDataLoader;
    @Mock KycDataMaskingService dataMaskingService;

    private KycChatContextService service;
    private final CurrentUserPrincipal principal =
            new CurrentUserPrincipal("USER-1", "客户经理", RoleName.CUSTOMER_MANAGER);

    @BeforeEach
    void setUp() {
        service = new KycChatContextService(
                workflowStateMapper,
                artifactMapper,
                currentUserService,
                customerDataLoader,
                dataMaskingService,
                new KycChatAliasNormalizer(),
                new ObjectMapper());
    }

    @Test
    void bindsWorkflowPersonAndLatestKycArtifact() {
        WorkflowState workflow = workflow(1001L);
        AgentArtifact artifact = artifact("ART-1");
        when(workflowStateMapper.selectById("WF-1")).thenReturn(workflow);
        when(artifactMapper.selectById("ART-1")).thenReturn(artifact);
        when(artifactMapper.selectOne(any())).thenReturn(artifact);

        KycChatContext context = service.requireContext(principal, "WF-1", 1001L, "ART-1");

        verify(currentUserService).requireCustomerAccess(principal, 1001L);
        assertThat(context.workflowId()).isEqualTo("WF-1");
        assertThat(context.personId()).isEqualTo(1001L);
        assertThat(context.kycArtifactId()).isEqualTo("ART-1");
        assertThat(context.aliasMappings()).containsEntry("P-1", "张三");
        assertThat(context.kycAnalysisJson()).contains("已有结论");
    }

    @Test
    void rejectsPersonIdThatDoesNotBelongToWorkflow() {
        when(workflowStateMapper.selectById("WF-1")).thenReturn(workflow(1001L));

        assertThatThrownBy(() -> service.requireContext(principal, "WF-1", 2002L, "ART-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("personId");
    }

    @Test
    void preparesManagerMessageAndCurrentDataWithPersistedAliasBaseline() {
        KycCustomerData customerData = mock(KycCustomerData.class);
        KycMaskedInput snapshot = new KycMaskedInput(
                Map.of("person", Map.of("personAlias", "P-1"),
                        "enterprise", Map.of("enterpriseAlias", "E-1")),
                Map.of(),
                Set.of(),
                Map.of("P-1", "张三", "E-1", "新增企业"),
                "new-hash");
        Map<String, String> messageMappings = new LinkedHashMap<>();
        messageMappings.put("P-1", "张三");
        messageMappings.put("E-1", "新增企业");
        messageMappings.put("E-2", "补充企业");
        KycMaskedInput messageInput = new KycMaskedInput(
                Map.of("managerInstruction", "请核对P-1、E-1与E-2"),
                Map.of(),
                Set.of(),
                messageMappings,
                "message-hash");
        when(customerDataLoader.load(1001L)).thenReturn(customerData);
        when(dataMaskingService.mask(customerData)).thenReturn(snapshot);
        when(dataMaskingService.mask(eq(customerData), any(KycRuntimeSupplement.class)))
                .thenReturn(messageInput);
        KycChatContext context = new KycChatContext(
                "WF-1", 1001L, "ART-1", "{}", "old-hash",
                Map.of("P-1", "张三", "E-1", "原有企业"));

        KycChatPreparedTurn prepared = service.prepareTurn(
                context, "请核对张三与新增企业", context.aliasMappings());

        assertThat(prepared.maskedMessage()).isEqualTo("请核对P-1、E-2与E-3");
        assertThat(prepared.currentMaskedData().toString()).contains("enterpriseAlias=E-2");
        assertThat(prepared.snapshotComparison())
                .isEqualTo(KycChatContextService.CURRENT_DATA_CHANGED_SINCE_KYC);
        assertThat(prepared.aliasMappings())
                .containsEntry("E-1", "原有企业")
                .containsEntry("E-2", "新增企业")
                .containsEntry("E-3", "补充企业");
    }

    private WorkflowState workflow(Long personId) {
        WorkflowState workflow = new WorkflowState();
        workflow.setWorkflowId("WF-1");
        workflow.setPersonId(personId);
        return workflow;
    }

    private AgentArtifact artifact(String artifactId) {
        AgentArtifact artifact = new AgentArtifact();
        artifact.setArtifactId(artifactId);
        artifact.setWorkflowId("WF-1");
        artifact.setAgentType(AgentType.CUSTOMER_INSIGHT);
        artifact.setVersion(1);
        artifact.setResult("""
                {"contractVersion":"kyc-result.v2","maskedInputSha256":"old-hash",
                 "aliasMappings":{"P-1":"张三"},"analysis":{"summary":"已有结论"}}
                """);
        return artifact;
    }
}
