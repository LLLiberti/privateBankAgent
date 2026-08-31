package com.privatebank.business.service.workflow;

import com.privatebank.agent.application.runtime.AgentExecutionCompletedEvent;
import com.privatebank.agent.application.runtime.AgentExecutionFailedEvent;
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

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowAgentStateServiceTest {

    @Test
    void claimsDownstreamAgentWithoutRewritingWorkflow() {
        Fixture fixture = fixture();
        WorkflowState workflow = workflow(WorkflowStatus.RUNNING);
        AgentState state = agentState(AgentType.MARKET_INSIGHT, AgentStatus.READY, "OLD");
        when(fixture.workflowMapper.selectById("WF-1")).thenReturn(workflow);
        when(fixture.agentStateMapper.selectOne(any())).thenReturn(state);
        when(fixture.agentStateMapper.updateById(any(AgentState.class))).thenReturn(1);

        Optional<WorkflowAgentExecutionClaim> result = fixture.service.claim("WF-1", AgentType.MARKET_INSIGHT);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().executionId()).startsWith("EXE-");
        assertThat(state.getAgentStatus()).isEqualTo(AgentStatus.RUNNING);
        assertThat(state.getExecutionId()).isEqualTo(result.orElseThrow().executionId());
        verify(fixture.workflowMapper, never()).updateById(any(WorkflowState.class));
    }

    @Test
    void claimsCustomerInsightAndStartsCreatedWorkflow() {
        Fixture fixture = fixture();
        WorkflowState workflow = workflow(WorkflowStatus.CREATED);
        AgentState state = agentState(AgentType.CUSTOMER_INSIGHT, AgentStatus.READY, null);
        when(fixture.workflowMapper.selectById("WF-1")).thenReturn(workflow);
        when(fixture.agentStateMapper.selectOne(any())).thenReturn(state);
        when(fixture.agentStateMapper.updateById(any(AgentState.class))).thenReturn(1);
        when(fixture.workflowMapper.updateById(any(WorkflowState.class))).thenReturn(1);

        assertThat(fixture.service.claim("WF-1", AgentType.CUSTOMER_INSIGHT)).isPresent();

        assertThat(workflow.getWorkflowStatus()).isEqualTo(WorkflowStatus.RUNNING);
        assertThat(workflow.getStartTime()).isNotNull();
        verify(fixture.workflowMapper).updateById(workflow);
    }

    @Test
    void doesNotClaimDownstreamAgentOutsideRunningWorkflow() {
        Fixture fixture = fixture();
        when(fixture.workflowMapper.selectById("WF-1")).thenReturn(workflow(WorkflowStatus.CREATED));

        assertThat(fixture.service.claim("WF-1", AgentType.PRODUCT_EXPERT)).isEmpty();

        verify(fixture.agentStateMapper, never()).selectOne(any());
    }

    @Test
    void claimsDifferentAgentsConcurrentlyWithoutCompetingForWorkflowState() throws Exception {
        Fixture fixture = fixture();
        ThreadLocal<AgentState> selectedState = new ThreadLocal<>();
        AgentState market = agentState(AgentType.MARKET_INSIGHT, AgentStatus.READY, null);
        AgentState product = agentState(AgentType.PRODUCT_EXPERT, AgentStatus.READY, null);
        when(fixture.workflowMapper.selectById("WF-1")).thenAnswer(ignored -> workflow(WorkflowStatus.RUNNING));
        when(fixture.agentStateMapper.selectOne(any())).thenAnswer(ignored -> selectedState.get());
        when(fixture.agentStateMapper.updateById(any(AgentState.class))).thenReturn(1);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<WorkflowAgentExecutionClaim>> marketClaim = executor.submit(() -> {
                selectedState.set(market);
                ready.countDown();
                start.await();
                return fixture.service.claim("WF-1", AgentType.MARKET_INSIGHT);
            });
            Future<Optional<WorkflowAgentExecutionClaim>> productClaim = executor.submit(() -> {
                selectedState.set(product);
                ready.countDown();
                start.await();
                return fixture.service.claim("WF-1", AgentType.PRODUCT_EXPERT);
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(marketClaim.get(5, TimeUnit.SECONDS)).isPresent();
            assertThat(productClaim.get(5, TimeUnit.SECONDS)).isPresent();
            verify(fixture.workflowMapper, never()).updateById(any(WorkflowState.class));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void completesOnlyCurrentClaimAndPersistsVersionedArtifact() {
        Fixture fixture = fixture();
        WorkflowState workflow = workflow(WorkflowStatus.RUNNING);
        AgentState state = agentState(AgentType.PRODUCT_EXPERT, AgentStatus.RUNNING, "EXE-1");
        when(fixture.workflowMapper.selectById("WF-1")).thenReturn(workflow);
        when(fixture.agentStateMapper.selectById("AS-PRODUCT_EXPERT")).thenReturn(state);
        when(fixture.artifactMapper.selectOne(any())).thenReturn(null);
        when(fixture.artifactMapper.insert(any(AgentArtifact.class))).thenReturn(1);
        when(fixture.agentStateMapper.updateById(any(AgentState.class))).thenReturn(1);
        AgentExecutionCompletedEvent event = new AgentExecutionCompletedEvent(
                "WF-1", "AS-PRODUCT_EXPERT", AgentType.PRODUCT_EXPERT, "EXE-1", "{}", null, 2);

        Optional<WorkflowAgentStateService.PersistedAgentResult> result = fixture.service.complete(event);

        assertThat(result).isPresent();
        ArgumentCaptor<AgentArtifact> artifact = ArgumentCaptor.forClass(AgentArtifact.class);
        verify(fixture.artifactMapper).insert(artifact.capture());
        assertThat(artifact.getValue().getVersion()).isEqualTo(1);
        assertThat(artifact.getValue().getExecutionId()).isEqualTo("EXE-1");
        assertThat(state.getAgentStatus()).isEqualTo(AgentStatus.SUCCESS);
        assertThat(state.getRetryCount()).isEqualTo(2);
    }

    @Test
    void ignoresCompletionFromStaleExecution() {
        Fixture fixture = fixture();
        when(fixture.workflowMapper.selectById("WF-1")).thenReturn(workflow(WorkflowStatus.RUNNING));
        when(fixture.agentStateMapper.selectById("AS-MARKET_INSIGHT"))
                .thenReturn(agentState(AgentType.MARKET_INSIGHT, AgentStatus.RUNNING, "EXE-CURRENT"));

        assertThat(fixture.service.complete(new AgentExecutionCompletedEvent(
                "WF-1", "AS-MARKET_INSIGHT", AgentType.MARKET_INSIGHT, "EXE-STALE", "{}", null, 0)))
                .isEmpty();

        verify(fixture.artifactMapper, never()).insert(any(AgentArtifact.class));
        verify(fixture.agentStateMapper, never()).updateById(any(AgentState.class));
    }

    @Test
    void failsCurrentClaimAndReadyClearsFailure() {
        Fixture fixture = fixture();
        AgentState state = agentState(AgentType.COMPLIANCE_CHECK, AgentStatus.RUNNING, "EXE-1");
        when(fixture.workflowMapper.selectById("WF-1")).thenReturn(workflow(WorkflowStatus.RUNNING));
        when(fixture.agentStateMapper.selectById("AS-COMPLIANCE_CHECK")).thenReturn(state);
        when(fixture.agentStateMapper.selectOne(any())).thenReturn(state);
        when(fixture.agentStateMapper.updateById(any(AgentState.class))).thenReturn(1);

        assertThat(fixture.service.fail(new AgentExecutionFailedEvent(
                "WF-1", "AS-COMPLIANCE_CHECK", AgentType.COMPLIANCE_CHECK, "EXE-1", "FAILED", "model")))
                .isPresent();
        assertThat(state.getAgentStatus()).isEqualTo(AgentStatus.FAILED);
        assertThat(state.getErrorCode()).isEqualTo("FAILED");

        fixture.service.ready("WF-1", AgentType.COMPLIANCE_CHECK);
        assertThat(state.getAgentStatus()).isEqualTo(AgentStatus.READY);
        assertThat(state.getErrorCode()).isNull();
        assertThat(state.getErrorMessage()).isNull();
        assertThat(state.getStartTime()).isNull();
        assertThat(state.getFinishTime()).isNull();
        verify(fixture.agentStateMapper, times(2)).updateById(state);
    }

    private Fixture fixture() {
        WorkflowStateMapper workflowMapper = mock(WorkflowStateMapper.class);
        AgentStateMapper agentStateMapper = mock(AgentStateMapper.class);
        AgentArtifactMapper artifactMapper = mock(AgentArtifactMapper.class);
        return new Fixture(workflowMapper, agentStateMapper, artifactMapper,
                new WorkflowAgentStateService(workflowMapper, agentStateMapper, artifactMapper));
    }

    private WorkflowState workflow(WorkflowStatus status) {
        WorkflowState workflow = new WorkflowState();
        workflow.setWorkflowId("WF-1");
        workflow.setPersonId(100L);
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
        state.setRetryCount(0);
        state.setStartTime(LocalDateTime.now());
        state.setVersion(0L);
        return state;
    }

    private record Fixture(
            WorkflowStateMapper workflowMapper,
            AgentStateMapper agentStateMapper,
            AgentArtifactMapper artifactMapper,
            WorkflowAgentStateService service) {
    }
}
