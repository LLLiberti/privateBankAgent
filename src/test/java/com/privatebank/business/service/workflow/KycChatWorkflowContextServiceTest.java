package com.privatebank.business.service.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.agent.application.kycchat.KycChatContext;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KycChatWorkflowContextServiceTest {

    @Mock WorkflowStateMapper workflowStateMapper;
    @Mock AgentArtifactMapper artifactMapper;
    @Mock CurrentUserService currentUserService;

    private KycChatWorkflowContextService service;
    private final CurrentUserPrincipal principal =
            new CurrentUserPrincipal("USER-1", "客户经理", RoleName.CUSTOMER_MANAGER);

    @BeforeEach
    void setUp() {
        service = new KycChatWorkflowContextService(
                workflowStateMapper, artifactMapper, currentUserService, new ObjectMapper());
    }

    @Test
    void bindsAuthorizedWorkflowPersonAndLatestKycArtifact() {
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
