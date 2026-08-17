package com.privatebank.agent.infrastructure.kyc;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.agent.application.kyc.KycExecutionClaim;
import com.privatebank.agent.domain.event.AgentSucceededEvent;
import com.privatebank.agent.domain.kyc.KycGenerationResult;
import com.privatebank.agent.domain.kyc.KycMaskedInput;
import com.privatebank.business.entity.workflow.AgentArtifact;
import com.privatebank.business.entity.workflow.AgentState;
import com.privatebank.business.entity.workflow.WorkflowState;
import com.privatebank.business.enums.workflow.AgentStatus;
import com.privatebank.business.enums.workflow.AgentType;
import com.privatebank.business.enums.workflow.WorkflowStatus;
import com.privatebank.business.mapper.workflow.AgentArtifactMapper;
import com.privatebank.business.mapper.workflow.AgentStateMapper;
import com.privatebank.business.mapper.workflow.WorkflowStateMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KycWorkflowStateServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void persistsKycArtifactAndPublishesOutcomeWithoutAdvancingWorkflow() throws Exception {
        WorkflowStateMapper workflowMapper = mock(WorkflowStateMapper.class);
        AgentStateMapper agentStateMapper = mock(AgentStateMapper.class);
        AgentArtifactMapper artifactMapper = mock(AgentArtifactMapper.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        KycWorkflowStateService service = new KycWorkflowStateService(
                workflowMapper, agentStateMapper, artifactMapper, eventPublisher, objectMapper);
        WorkflowState workflow = workflow();
        AgentState agentState = readyAgentState();
        when(workflowMapper.selectById("WF-1")).thenReturn(workflow);
        when(agentStateMapper.selectOne(org.mockito.ArgumentMatchers.<Wrapper<AgentState>>any())).thenReturn(agentState);
        when(artifactMapper.selectOne(org.mockito.ArgumentMatchers.<Wrapper<AgentArtifact>>any())).thenReturn(null);
        when(artifactMapper.insert(any(AgentArtifact.class))).thenReturn(1);
        when(agentStateMapper.updateById(any(AgentState.class))).thenReturn(1);
        when(workflowMapper.updateById(any(WorkflowState.class))).thenReturn(1);

        KycExecutionClaim claim = service.claim("WF-1").orElseThrow();

        assertThat(workflow.getWorkflowStatus()).isEqualTo(WorkflowStatus.RUNNING);
        assertThat(agentState.getAgentStatus()).isEqualTo(AgentStatus.RUNNING);
        assertThat(agentState.getExecutionId()).isEqualTo(claim.executionId());

        boolean completed = service.complete(claim, maskedInput(), generationResult());

        assertThat(completed).isTrue();
        assertThat(workflow.getWorkflowStatus()).isEqualTo(WorkflowStatus.RUNNING);
        assertThat(agentState.getAgentStatus()).isEqualTo(AgentStatus.SUCCESS);
        assertThat(agentState.getExecutionId()).isEqualTo(claim.executionId());
        assertThat(agentState.getRetryCount()).isEqualTo(1);
        assertThat(agentState.getFinishTime()).isNotNull();

        ArgumentCaptor<AgentArtifact> artifactCaptor = ArgumentCaptor.forClass(AgentArtifact.class);
        verify(artifactMapper).insert(artifactCaptor.capture());
        AgentArtifact artifact = artifactCaptor.getValue();
        assertThat(artifact.getWorkflowId()).isEqualTo("WF-1");
        assertThat(artifact.getAgentStateId()).isEqualTo("AS-1");
        assertThat(artifact.getAgentType()).isEqualTo(AgentType.CUSTOMER_INSIGHT);
        assertThat(artifact.getExecutionId()).isEqualTo(claim.executionId());
        assertThat(artifact.getVersion()).isEqualTo(1);
        JsonNode savedResult = objectMapper.readTree(artifact.getResult());
        assertThat(savedResult.path("maskingApplied").asBoolean()).isTrue();
        assertThat(savedResult.path("aliasMappings").path("P-1").asText()).isEqualTo("张三");
        assertThat(savedResult.path("aliasMappings").path("E-1").asText()).isEqualTo("某某科技有限公司");
        assertThat(savedResult.path("analysis").path("riskLevel").asText()).isEqualTo("MEDIUM");
        assertThat(savedResult.toString()).doesNotContain("managerSupplement", "客户经理补充的原始内容");

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOf(AgentSucceededEvent.class);
        AgentSucceededEvent event = (AgentSucceededEvent) eventCaptor.getValue();
        assertThat(event.workflowId()).isEqualTo("WF-1");
        assertThat(event.agentStateId()).isEqualTo("AS-1");
        assertThat(event.executionId()).isEqualTo(claim.executionId());
        assertThat(event.artifactId()).isEqualTo(artifact.getArtifactId());
    }

    private WorkflowState workflow() {
        WorkflowState workflow = new WorkflowState();
        workflow.setWorkflowId("WF-1");
        workflow.setPersonId(100L);
        workflow.setWorkflowStatus(WorkflowStatus.CREATED);
        workflow.setVersion(0L);
        workflow.setUpdatedAt(LocalDateTime.now().minusMinutes(1));
        return workflow;
    }

    private AgentState readyAgentState() {
        AgentState state = new AgentState();
        state.setAgentStateId("AS-1");
        state.setWorkflowId("WF-1");
        state.setAgentType(AgentType.CUSTOMER_INSIGHT);
        state.setAgentStatus(AgentStatus.READY);
        state.setRetryCount(0);
        state.setVersion(0L);
        return state;
    }

    private KycMaskedInput maskedInput() {
        return new KycMaskedInput(
                Map.of("person", Map.of(), "managerSupplement", Map.of("signals", Set.of("LIQUIDITY_NEED"))),
                Map.of("SRC-1", 1001L),
                Set.of("客户经理补充的原始内容"),
                Map.of("P-1", "张三", "E-1", "某某科技有限公司"),
                "a".repeat(64));
    }

    private KycGenerationResult generationResult() {
        return new KycGenerationResult(
                "{\"riskLevel\":\"MEDIUM\",\"summary\":\"Masked analysis\",\"findings\":[],\"riskAlerts\":[],\"recommendedActions\":[],\"dataGaps\":[]}",
                2,
                "deepseek-v4-flash");
    }
}
