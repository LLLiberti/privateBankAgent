package com.privatebank.agent.infrastructure.workflow;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.privatebank.agent.application.runtime.AgentExecutionClaim;
import com.privatebank.agent.domain.event.AgentFailedEvent;
import com.privatebank.agent.domain.event.AgentSucceededEvent;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentWorkflowStateServiceTest {

    @Test
    void claimsOnlyReadyNonTerminalAgentAndMovesItToRunning() {
        Fixture fixture = fixture();
        WorkflowState workflow = workflow(WorkflowStatus.CREATED);
        AgentState state = agentState(AgentType.MARKET_INSIGHT, AgentStatus.READY, "OLD");
        when(fixture.workflowMapper.selectById("WF-1")).thenReturn(workflow);
        when(fixture.agentStateMapper.selectOne(any())).thenReturn(state);
        when(fixture.agentStateMapper.updateById(any(AgentState.class))).thenReturn(1);
        when(fixture.workflowMapper.updateById(any(WorkflowState.class))).thenReturn(1);

        Optional<AgentExecutionClaim> result = fixture.service().claim("WF-1", AgentType.MARKET_INSIGHT);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().executionId()).startsWith("EXE-");
        assertThat(state.getAgentStatus()).isEqualTo(AgentStatus.RUNNING);
        assertThat(state.getExecutionId()).isEqualTo(result.orElseThrow().executionId());
        assertThat(state.getStartTime()).isNotNull();
        assertThat(workflow.getWorkflowStatus()).isEqualTo(WorkflowStatus.RUNNING);
        assertThat(workflow.getStartTime()).isNotNull();
    }

    @Test
    void doesNotClaimRunningOrTerminalAgent() {
        Fixture fixture = fixture();
        WorkflowState workflow = workflow(WorkflowStatus.RUNNING);
        AgentState running = agentState(AgentType.MARKET_INSIGHT, AgentStatus.RUNNING, "EXE-1");
        when(fixture.workflowMapper.selectById("WF-1")).thenReturn(workflow);
        when(fixture.agentStateMapper.selectOne(any())).thenReturn(running);

        assertThat(fixture.service().claim("WF-1", AgentType.MARKET_INSIGHT)).isEmpty();
        verify(fixture.agentStateMapper, never()).updateById(any(AgentState.class));

        when(fixture.workflowMapper.selectById("WF-1")).thenReturn(workflow(WorkflowStatus.CANCELED));

        assertThat(fixture.service().claim("WF-1", AgentType.MARKET_INSIGHT)).isEmpty();
        verify(fixture.agentStateMapper, times(1)).selectOne(any());
    }

    @Test
    void completesOnlyTheCurrentClaimAndPublishesSuccess() {
        Fixture fixture = fixture();
        WorkflowState workflow = workflow(WorkflowStatus.RUNNING);
        AgentState state = agentState(AgentType.PRODUCT_EXPERT, AgentStatus.RUNNING, "EXE-1");
        AgentExecutionClaim claim = new AgentExecutionClaim(
                "WF-1", "AS-PRODUCT_EXPERT", AgentType.PRODUCT_EXPERT, "EXE-1", "USER-1");
        when(fixture.workflowMapper.selectById("WF-1")).thenReturn(workflow);
        when(fixture.agentStateMapper.selectById("AS-PRODUCT_EXPERT")).thenReturn(state);
        when(fixture.artifactMapper.selectOne(any())).thenReturn(null);
        when(fixture.artifactMapper.insert(any(AgentArtifact.class))).thenReturn(1);
        when(fixture.agentStateMapper.updateById(any(AgentState.class))).thenReturn(1);

        assertThat(fixture.service().complete(claim, "{\"candidateProductIds\":[\"P-1\"]}", null)).isTrue();

        ArgumentCaptor<AgentArtifact> artifactCaptor = ArgumentCaptor.forClass(AgentArtifact.class);
        verify(fixture.artifactMapper).insert(artifactCaptor.capture());
        AgentArtifact artifact = artifactCaptor.getValue();
        assertThat(artifact.getAgentType()).isEqualTo(AgentType.PRODUCT_EXPERT);
        assertThat(artifact.getExecutionId()).isEqualTo("EXE-1");
        assertThat(artifact.getVersion()).isEqualTo(1);
        assertThat(state.getAgentStatus()).isEqualTo(AgentStatus.SUCCESS);
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(fixture.eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isEqualTo(new AgentSucceededEvent(
                "WF-1", "AS-PRODUCT_EXPERT", AgentType.PRODUCT_EXPERT, "EXE-1", artifact.getArtifactId()));
    }

    @Test
    void ignoresCompletionFromStaleExecution() {
        Fixture fixture = fixture();
        WorkflowState workflow = workflow(WorkflowStatus.RUNNING);
        AgentState state = agentState(AgentType.MARKET_INSIGHT, AgentStatus.RUNNING, "EXE-CURRENT");
        AgentExecutionClaim stale = new AgentExecutionClaim(
                "WF-1", "AS-MARKET_INSIGHT", AgentType.MARKET_INSIGHT, "EXE-STALE", "USER-1");
        when(fixture.workflowMapper.selectById("WF-1")).thenReturn(workflow);
        when(fixture.agentStateMapper.selectById("AS-MARKET_INSIGHT")).thenReturn(state);

        assertThat(fixture.service().complete(stale, "{}", null)).isFalse();

        verify(fixture.artifactMapper, never()).insert(any(AgentArtifact.class));
        verify(fixture.agentStateMapper, never()).updateById(any(AgentState.class));
        verify(fixture.eventPublisher, never()).publishEvent(any());
    }

    @Test
    void marksCurrentClaimFailedAndPublishesFailure() {
        Fixture fixture = fixture();
        WorkflowState workflow = workflow(WorkflowStatus.RUNNING);
        AgentState state = agentState(AgentType.COMPLIANCE_CHECK, AgentStatus.RUNNING, "EXE-1");
        AgentExecutionClaim claim = new AgentExecutionClaim(
                "WF-1", "AS-COMPLIANCE_CHECK", AgentType.COMPLIANCE_CHECK, "EXE-1", "USER-1");
        when(fixture.workflowMapper.selectById("WF-1")).thenReturn(workflow);
        when(fixture.agentStateMapper.selectById("AS-COMPLIANCE_CHECK")).thenReturn(state);
        when(fixture.agentStateMapper.updateById(any(AgentState.class))).thenReturn(1);

        assertThat(fixture.service().fail(claim, "COMPLIANCE_CHECK_EXECUTION_FAILED", "model unavailable"))
                .isTrue();

        assertThat(state.getAgentStatus()).isEqualTo(AgentStatus.FAILED);
        assertThat(state.getErrorCode()).isEqualTo("COMPLIANCE_CHECK_EXECUTION_FAILED");
        assertThat(state.getErrorMessage()).isEqualTo("model unavailable");
        verify(fixture.eventPublisher).publishEvent(new AgentFailedEvent(
                "WF-1", "AS-COMPLIANCE_CHECK", AgentType.COMPLIANCE_CHECK, "EXE-1",
                "COMPLIANCE_CHECK_EXECUTION_FAILED"));
    }

    @Test
    void readyClearsPreviousFailureState() {
        Fixture fixture = fixture();
        AgentState state = agentState(AgentType.SOLUTION_DESIGN, AgentStatus.FAILED, "EXE-OLD");
        state.setErrorCode("CFS_DESIGN_EXECUTION_FAILED");
        state.setErrorMessage("failed");
        state.setStartTime(LocalDateTime.now().minusMinutes(1));
        state.setFinishTime(LocalDateTime.now());
        when(fixture.agentStateMapper.selectOne(any())).thenReturn(state);
        when(fixture.agentStateMapper.updateById(any(AgentState.class))).thenReturn(1);

        fixture.service().ready("WF-1", AgentType.SOLUTION_DESIGN);

        assertThat(state.getAgentStatus()).isEqualTo(AgentStatus.READY);
        assertThat(state.getErrorCode()).isNull();
        assertThat(state.getErrorMessage()).isNull();
        assertThat(state.getStartTime()).isNull();
        assertThat(state.getFinishTime()).isNull();
    }

    private Fixture fixture() {
        return new Fixture(
                mock(WorkflowStateMapper.class),
                mock(AgentStateMapper.class),
                mock(AgentArtifactMapper.class),
                mock(ApplicationEventPublisher.class));
    }

    private WorkflowState workflow(WorkflowStatus status) {
        WorkflowState workflow = new WorkflowState();
        workflow.setWorkflowId("WF-1");
        workflow.setCreatedBy("USER-1");
        workflow.setWorkflowStatus(status);
        workflow.setVersion(0L);
        return workflow;
    }

    private AgentState agentState(AgentType type, AgentStatus status, String executionId) {
        AgentState state = new AgentState();
        state.setAgentStateId("AS-" + type.name());
        state.setWorkflowId("WF-1");
        state.setAgentType(type);
        state.setAgentStatus(status);
        state.setExecutionId(executionId);
        state.setVersion(0L);
        return state;
    }

    private record Fixture(
            WorkflowStateMapper workflowMapper,
            AgentStateMapper agentStateMapper,
            AgentArtifactMapper artifactMapper,
            ApplicationEventPublisher eventPublisher) {

        private AgentWorkflowStateService service() {
            return new AgentWorkflowStateService(
                    workflowMapper, agentStateMapper, artifactMapper, eventPublisher);
        }

        private static Fixture of(
                WorkflowStateMapper workflowMapper,
                AgentStateMapper agentStateMapper,
                AgentArtifactMapper artifactMapper,
                ApplicationEventPublisher eventPublisher) {
            return new Fixture(workflowMapper, agentStateMapper, artifactMapper, eventPublisher);
        }
    }

    private AgentWorkflowStateService service(Fixture fixture) {
        return fixture.service();
    }
}
