package com.privatebank.agent.application.downstream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.agent.application.runtime.AgentExecutionCompletedEvent;
import com.privatebank.agent.application.runtime.AgentExecutionFailedEvent;
import com.privatebank.agent.application.runtime.AgentExecutionRequest;
import com.privatebank.agent.application.runtime.AgentExecutionRequestedEvent;
import com.privatebank.agent.application.runtime.AgentExecutionResult;
import com.privatebank.agent.domain.downstream.CfsDesignResult;
import com.privatebank.agent.domain.downstream.ComplianceCheckResult;
import com.privatebank.agent.domain.downstream.KypRecommendationResult;
import com.privatebank.agent.domain.downstream.MarketInsightResult;
import com.privatebank.business.enums.workflow.AgentType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DownstreamAgentExecutionServiceTest {

    @Test
    void executesMarketCapabilityFromWorkflowPreparedInput() {
        Fixture fixture = fixture();
        when(fixture.marketExecutor.execute(any())).thenReturn(new AgentExecutionResult<>(
                new MarketInsightResult("C-1", "ART-KYC", List.of(), List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of(), List.of("SRC-1")),
                1, "model"));

        fixture.service.execute(event(
                AgentType.MARKET_INSIGHT,
                Map.of("kycArtifactId", "ART-KYC"),
                Map.of("kycArtifactId", "{\"analysis\":{}}")));

        ArgumentCaptor<AgentExecutionRequest> request = ArgumentCaptor.forClass(AgentExecutionRequest.class);
        verify(fixture.marketExecutor).execute(request.capture());
        assertThat(request.getValue().input())
                .isInstanceOf(com.privatebank.agent.domain.downstream.MarketInsightInput.class);
        assertThat(published(fixture)).isInstanceOf(AgentExecutionCompletedEvent.class);
    }

    @Test
    void executesProductCapabilityWithoutOwningWorkflowState() {
        Fixture fixture = fixture();
        when(fixture.productExecutor.execute(any())).thenReturn(new AgentExecutionResult<>(
                new KypRecommendationResult("KYP", "C-1", "ART-KYC", List.of(), List.of(), List.of(), List.of(),
                        List.of(), List.of()),
                1, "model"));

        fixture.service.execute(event(
                AgentType.PRODUCT_EXPERT,
                Map.of("kycArtifactId", "ART-KYC"),
                Map.of("kycArtifactId", "{}")));

        ArgumentCaptor<AgentExecutionRequest> request = ArgumentCaptor.forClass(AgentExecutionRequest.class);
        verify(fixture.productExecutor).execute(request.capture());
        com.privatebank.agent.domain.downstream.ProductExpertInput input =
                (com.privatebank.agent.domain.downstream.ProductExpertInput) request.getValue().input();
        assertThat(input.candidateProductIds()).isEmpty();
        assertThat(input.productKnowledge()).isEmpty();
        assertThat(published(fixture)).isInstanceOf(AgentExecutionCompletedEvent.class);
    }

    @Test
    void validatesCfsOutputBeforePublishingCompletion() {
        Fixture fixture = fixture();
        CfsDesignResult result = validCfs();
        when(fixture.cfsExecutor.execute(any())).thenReturn(new AgentExecutionResult<>(result, 1, "model"));

        fixture.service.execute(event(
                AgentType.SOLUTION_DESIGN,
                Map.of("kycArtifactId", "ART-KYC", "marketArtifactId", "ART-MARKET", "kypArtifactId", "ART-KYP"),
                Map.of("kycArtifactId", "kyc", "marketArtifactId", "market", "kypArtifactId", "kyp")));

        verify(fixture.cfsValidator).validate(result);
        assertThat(published(fixture)).isInstanceOf(AgentExecutionCompletedEvent.class);
    }

    @Test
    void publishesComplianceDecisionWithoutPersistingIt() {
        Fixture fixture = fixture();
        when(fixture.complianceExecutor.execute(any())).thenReturn(new AgentExecutionResult<>(
                new ComplianceCheckResult(
                        "ART-CFS", "PASS", "passed", List.of(), List.of(), List.of(), List.of()),
                1, "model"));

        fixture.service.execute(event(
                AgentType.COMPLIANCE_CHECK,
                Map.of("cfsArtifactId", "ART-CFS"),
                Map.of("cfsArtifactId", "{\"cfsVersion\":1}")));

        AgentExecutionCompletedEvent completed = (AgentExecutionCompletedEvent) published(fixture);
        assertThat(completed.complianceResult()).isEqualTo("PASS");
    }

    @Test
    void publishesFailureWhenWorkflowPreparedInputIsIncomplete() {
        Fixture fixture = fixture();

        fixture.service.execute(event(
                AgentType.MARKET_INSIGHT,
                Map.of("kycArtifactId", "ART-MISSING"),
                Map.of()));

        AgentExecutionFailedEvent failed = (AgentExecutionFailedEvent) published(fixture);
        assertThat(failed.errorCode()).isEqualTo("MARKET_INSIGHT_EXECUTION_FAILED");
        assertThat(failed.errorMessage()).contains("Agent输入缺少Artifact结果");
    }

    private Object published(Fixture fixture) {
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(fixture.publisher).publishEvent(event.capture());
        return event.getValue();
    }

    private Fixture fixture() {
        MarketInsightAgentExecutor marketExecutor = mock(MarketInsightAgentExecutor.class);
        ProductExpertAgentExecutor productExecutor = mock(ProductExpertAgentExecutor.class);
        CfsDesignAgentExecutor cfsExecutor = mock(CfsDesignAgentExecutor.class);
        CfsDesignResultValidator cfsValidator = mock(CfsDesignResultValidator.class);
        ComplianceCheckAgentExecutor complianceExecutor = mock(ComplianceCheckAgentExecutor.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        DownstreamAgentExecutionService service = new DownstreamAgentExecutionService(
                marketExecutor, productExecutor, cfsExecutor, cfsValidator,
                complianceExecutor, new ObjectMapper().findAndRegisterModules(), publisher);
        return new Fixture(
                marketExecutor, productExecutor, cfsExecutor, cfsValidator, complianceExecutor, publisher, service);
    }

    private AgentExecutionRequestedEvent event(
            AgentType agentType,
            Map<String, String> artifactIds,
            Map<String, String> artifactResults) {
        return new AgentExecutionRequestedEvent(
                "WF-1", "AS-" + agentType, agentType, "EXE-" + agentType,
                "USER-1", 100L, artifactIds, artifactResults, null, List.of(), List.of());
    }

    private CfsDesignResult validCfs() {
        return new CfsDesignResult(
                "C-1",
                new CfsDesignResult.InputArtifactRefs("ART-KYC", "ART-MARKET", "ART-KYP"),
                1,
                "strategy",
                "guide",
                "risk assessment",
                new CfsDesignResult.CfsStructure("customer", "service", "marketing", List.of(
                        "controller details", "company events and financial analysis",
                        "products and services", "industry and competitors",
                        "company and personal public opinion", "advantages and marketing language")),
                List.of(),
                List.of(),
                List.of("SRC-1"),
                List.of(),
                List.of("RULE-1"));
    }

    private record Fixture(
            MarketInsightAgentExecutor marketExecutor,
            ProductExpertAgentExecutor productExecutor,
            CfsDesignAgentExecutor cfsExecutor,
            CfsDesignResultValidator cfsValidator,
            ComplianceCheckAgentExecutor complianceExecutor,
            ApplicationEventPublisher publisher,
            DownstreamAgentExecutionService service) {
    }
}
