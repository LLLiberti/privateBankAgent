package com.privatebank.business.service.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.agent.domain.event.AgentExecutionRequestedEvent;
import com.privatebank.agent.domain.event.AgentSucceededEvent;
import com.privatebank.agent.infrastructure.workflow.AgentWorkflowStateService;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkflowAgentResultListenerDownstreamTest {

    @Test
    void waitsForTheSecondParallelAgentBeforeReleasingCfs() {
        Fixture fixture = fixture();
        AgentState market = agentState(AgentType.MARKET_INSIGHT, AgentStatus.SUCCESS, "EXE-MARKET");
        AgentState product = agentState(AgentType.PRODUCT_EXPERT, AgentStatus.RUNNING, "EXE-PRODUCT");
        when(fixture.workflowMapper.selectById("WF-1")).thenReturn(workflow());
        when(fixture.agentStateMapper.selectById("AS-MARKET_INSIGHT")).thenReturn(market);
        when(fixture.artifactMapper.selectById("ART-MARKET")).thenReturn(artifact(
                "ART-MARKET", AgentType.MARKET_INSIGHT, "EXE-MARKET", null));
        when(fixture.stateService.agentState("WF-1", AgentType.MARKET_INSIGHT)).thenReturn(market);
        when(fixture.stateService.agentState("WF-1", AgentType.PRODUCT_EXPERT)).thenReturn(product);

        fixture.listener().onAgentSucceeded(new AgentSucceededEvent(
                "WF-1", "AS-MARKET_INSIGHT", AgentType.MARKET_INSIGHT, "EXE-MARKET", "ART-MARKET"));

        verify(fixture.stateService, never()).ready("WF-1", AgentType.SOLUTION_DESIGN);
        verifyNoInteractions(fixture.eventPublisher);
    }

    @Test
    void releasesCfsWhenBothParallelAgentsSucceed() {
        Fixture fixture = fixture();
        AgentState market = agentState(AgentType.MARKET_INSIGHT, AgentStatus.SUCCESS, "EXE-MARKET");
        AgentState product = agentState(AgentType.PRODUCT_EXPERT, AgentStatus.SUCCESS, "EXE-PRODUCT");
        AgentArtifact kyc = artifact("ART-KYC", AgentType.CUSTOMER_INSIGHT, "EXE-KYC", "{}");
        AgentArtifact marketArtifact = artifact("ART-MARKET", AgentType.MARKET_INSIGHT, "EXE-MARKET", "{}");
        AgentArtifact productArtifact = artifact("ART-PRODUCT", AgentType.PRODUCT_EXPERT, "EXE-PRODUCT", "{}");
        when(fixture.workflowMapper.selectById("WF-1")).thenReturn(workflow());
        when(fixture.agentStateMapper.selectById("AS-MARKET_INSIGHT")).thenReturn(market);
        when(fixture.artifactMapper.selectById("ART-MARKET")).thenReturn(marketArtifact);
        when(fixture.stateService.agentState("WF-1", AgentType.MARKET_INSIGHT)).thenReturn(market);
        when(fixture.stateService.agentState("WF-1", AgentType.PRODUCT_EXPERT)).thenReturn(product);
        when(fixture.stateService.latestArtifact("WF-1", AgentType.CUSTOMER_INSIGHT)).thenReturn(kyc);
        when(fixture.stateService.latestArtifact("WF-1", AgentType.MARKET_INSIGHT)).thenReturn(marketArtifact);
        when(fixture.stateService.latestArtifact("WF-1", AgentType.PRODUCT_EXPERT)).thenReturn(productArtifact);

        fixture.listener().onAgentSucceeded(new AgentSucceededEvent(
                "WF-1", "AS-MARKET_INSIGHT", AgentType.MARKET_INSIGHT, "EXE-MARKET", "ART-MARKET"));

        verify(fixture.stateService).ready("WF-1", AgentType.SOLUTION_DESIGN);
        verify(fixture.eventPublisher).publishEvent(new AgentExecutionRequestedEvent(
                "WF-1", AgentType.SOLUTION_DESIGN, Map.of(
                        "kycArtifactId", "ART-KYC",
                        "marketArtifactId", "ART-MARKET",
                        "kypArtifactId", "ART-PRODUCT")));
    }

    @Test
    void releasesComplianceAfterCfsSucceeds() {
        Fixture fixture = fixture();
        AgentState cfsState = agentState(AgentType.SOLUTION_DESIGN, AgentStatus.SUCCESS, "EXE-CFS");
        AgentArtifact cfs = artifact("ART-CFS", AgentType.SOLUTION_DESIGN, "EXE-CFS", "{}");
        when(fixture.workflowMapper.selectById("WF-1")).thenReturn(workflow());
        when(fixture.agentStateMapper.selectById("AS-SOLUTION_DESIGN")).thenReturn(cfsState);
        when(fixture.artifactMapper.selectById("ART-CFS")).thenReturn(cfs);

        fixture.listener().onAgentSucceeded(new AgentSucceededEvent(
                "WF-1", "AS-SOLUTION_DESIGN", AgentType.SOLUTION_DESIGN, "EXE-CFS", "ART-CFS"));

        verify(fixture.stateService).ready("WF-1", AgentType.COMPLIANCE_CHECK);
        verify(fixture.eventPublisher).publishEvent(new AgentExecutionRequestedEvent(
                "WF-1", AgentType.COMPLIANCE_CHECK, Map.of("cfsArtifactId", "ART-CFS")));
    }

    @Test
    void movesToWaitingReviewWhenCompliancePasses() {
        Fixture fixture = fixture();
        AgentState state = agentState(AgentType.COMPLIANCE_CHECK, AgentStatus.SUCCESS, "EXE-COMPLIANCE");
        AgentArtifact artifact = artifact(
                "ART-COMPLIANCE", AgentType.COMPLIANCE_CHECK, "EXE-COMPLIANCE",
                "{\"cfsArtifactRef\":\"ART-CFS\"}");
        artifact.setComplianceResult("PASS");
        when(fixture.workflowMapper.selectById("WF-1")).thenReturn(workflow());
        when(fixture.agentStateMapper.selectById("AS-COMPLIANCE_CHECK")).thenReturn(state);
        when(fixture.artifactMapper.selectById("ART-COMPLIANCE")).thenReturn(artifact);
        when(fixture.workflowMapper.updateById(any(WorkflowState.class))).thenReturn(1);

        fixture.listener().onAgentSucceeded(new AgentSucceededEvent(
                "WF-1", "AS-COMPLIANCE_CHECK", AgentType.COMPLIANCE_CHECK, "EXE-COMPLIANCE", "ART-COMPLIANCE"));

        assertThat(fixture.workflowMapper.selectById("WF-1")).isNotNull();
        verify(fixture.workflowMapper).updateById(any(WorkflowState.class));
        assertThat(fixture.lastWorkflowStatus()).isEqualTo(WorkflowStatus.WAITING_REVIEW);
        verify(fixture.eventHub).publish(eq("WF-1"), eq("COMPLIANCE_PASSED"), any());
    }

    @Test
    void reopensCfsWhenComplianceRejects() {
        Fixture fixture = fixture();
        AgentState state = agentState(AgentType.COMPLIANCE_CHECK, AgentStatus.SUCCESS, "EXE-COMPLIANCE");
        AgentArtifact artifact = artifact(
                "ART-COMPLIANCE", AgentType.COMPLIANCE_CHECK, "EXE-COMPLIANCE",
                "{\"cfsArtifactRef\":\"ART-CFS\"}");
        artifact.setComplianceResult("REJECT");
        when(fixture.workflowMapper.selectById("WF-1")).thenReturn(workflow());
        when(fixture.agentStateMapper.selectById("AS-COMPLIANCE_CHECK")).thenReturn(state);
        when(fixture.artifactMapper.selectById("ART-COMPLIANCE")).thenReturn(artifact);
        when(fixture.workflowMapper.updateById(any(WorkflowState.class))).thenReturn(1);
        when(fixture.stateService.latestArtifact(any(), eq(AgentType.CUSTOMER_INSIGHT)))
                .thenReturn(artifact("ART-KYC", AgentType.CUSTOMER_INSIGHT, "EXE-KYC", "{}"));
        when(fixture.stateService.latestArtifact(any(), eq(AgentType.MARKET_INSIGHT)))
                .thenReturn(artifact("ART-MARKET", AgentType.MARKET_INSIGHT, "EXE-MARKET", "{}"));
        when(fixture.stateService.latestArtifact(any(), eq(AgentType.PRODUCT_EXPERT)))
                .thenReturn(artifact("ART-PRODUCT", AgentType.PRODUCT_EXPERT, "EXE-PRODUCT", "{}"));

        fixture.listener().onAgentSucceeded(new AgentSucceededEvent(
                "WF-1", "AS-COMPLIANCE_CHECK", AgentType.COMPLIANCE_CHECK, "EXE-COMPLIANCE", "ART-COMPLIANCE"));

        assertThat(fixture.lastWorkflowStatus()).isEqualTo(WorkflowStatus.RUNNING);
        verify(fixture.stateService).ready("WF-1", AgentType.SOLUTION_DESIGN);
        verify(fixture.eventPublisher).publishEvent(new AgentExecutionRequestedEvent(
                "WF-1", AgentType.SOLUTION_DESIGN, Map.of(
                        "kycArtifactId", "ART-KYC",
                        "marketArtifactId", "ART-MARKET",
                        "kypArtifactId", "ART-PRODUCT")));
    }

    @Test
    void asksForManagerInputWhenComplianceNeedsReview() {
        Fixture fixture = fixture();
        AgentState state = agentState(AgentType.COMPLIANCE_CHECK, AgentStatus.SUCCESS, "EXE-COMPLIANCE");
        AgentArtifact artifact = artifact(
                "ART-COMPLIANCE", AgentType.COMPLIANCE_CHECK, "EXE-COMPLIANCE", "{}");
        artifact.setComplianceResult("REVIEW_REQUIRED");
        when(fixture.workflowMapper.selectById("WF-1")).thenReturn(workflow());
        when(fixture.agentStateMapper.selectById("AS-COMPLIANCE_CHECK")).thenReturn(state);
        when(fixture.artifactMapper.selectById("ART-COMPLIANCE")).thenReturn(artifact);
        when(fixture.workflowMapper.updateById(any(WorkflowState.class))).thenReturn(1);

        fixture.listener().onAgentSucceeded(new AgentSucceededEvent(
                "WF-1", "AS-COMPLIANCE_CHECK", AgentType.COMPLIANCE_CHECK, "EXE-COMPLIANCE", "ART-COMPLIANCE"));

        assertThat(fixture.lastWorkflowStatus()).isEqualTo(WorkflowStatus.WAITING_INPUT);
        verify(fixture.eventHub).publish(eq("WF-1"), eq("COMPLIANCE_REVIEW_REQUIRED"), any());
        verifyNoInteractions(fixture.eventPublisher);
    }

    private Fixture fixture() {
        return new Fixture(
                mock(WorkflowStateMapper.class),
                mock(AgentStateMapper.class),
                mock(AgentArtifactMapper.class),
                mock(AgentWorkflowStateService.class),
                mock(WorkflowEventHub.class),
                mock(ApplicationEventPublisher.class));
    }

    private WorkflowState workflow() {
        WorkflowState workflow = new WorkflowState();
        workflow.setWorkflowId("WF-1");
        workflow.setWorkflowStatus(WorkflowStatus.RUNNING);
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

    private AgentArtifact artifact(String id, AgentType type, String executionId, String result) {
        AgentArtifact artifact = new AgentArtifact();
        artifact.setArtifactId(id);
        artifact.setWorkflowId("WF-1");
        artifact.setAgentStateId("AS-" + type.name());
        artifact.setAgentType(type);
        artifact.setExecutionId(executionId);
        artifact.setResult(result);
        return artifact;
    }

    private record Fixture(
            WorkflowStateMapper workflowMapper,
            AgentStateMapper agentStateMapper,
            AgentArtifactMapper artifactMapper,
            AgentWorkflowStateService stateService,
            WorkflowEventHub eventHub,
            ApplicationEventPublisher eventPublisher) {
        private WorkflowAgentResultListener listener() {
            return new WorkflowAgentResultListener(
                    workflowMapper, agentStateMapper, artifactMapper, stateService,
                    eventHub, eventPublisher, new ObjectMapper().findAndRegisterModules());
        }

        private WorkflowStatus lastWorkflowStatus() {
            return workflowMapper.selectById("WF-1").getWorkflowStatus();
        }
    }
}
