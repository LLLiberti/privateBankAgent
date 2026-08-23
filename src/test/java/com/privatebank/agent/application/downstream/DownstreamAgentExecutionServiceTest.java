package com.privatebank.agent.application.downstream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.agent.application.runtime.AgentExecutionClaim;
import com.privatebank.agent.application.runtime.AgentExecutionRequest;
import com.privatebank.agent.application.runtime.AgentExecutionResult;
import com.privatebank.agent.domain.downstream.CfsDesignResult;
import com.privatebank.agent.domain.downstream.ComplianceCheckResult;
import com.privatebank.agent.domain.downstream.KypRecommendationResult;
import com.privatebank.agent.domain.downstream.MarketInsightResult;
import com.privatebank.business.entity.workflow.AgentArtifact;
import com.privatebank.business.enums.workflow.AgentType;
import com.privatebank.business.mapper.workflow.AgentArtifactMapper;
import com.privatebank.agent.infrastructure.workflow.AgentWorkflowStateService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DownstreamAgentExecutionServiceTest {

    @Test
    void executesMarketInsightFromLatestKycArtifact() {
        Fixture fixture = fixture();
        AgentExecutionClaim claim = claim(AgentType.MARKET_INSIGHT);
        when(fixture.stateService.claim("WF-1", AgentType.MARKET_INSIGHT)).thenReturn(Optional.of(claim));
        when(fixture.artifactMapper.selectById("ART-KYC")).thenReturn(artifact(
                "ART-KYC", AgentType.CUSTOMER_INSIGHT, "{\"analysis\":{}}"));
        when(fixture.marketExecutor.execute(any())).thenReturn(new AgentExecutionResult<>(
                new MarketInsightResult("C-1", "ART-KYC", List.of(), List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of(), List.of("SRC-1")),
                1, "model"));

        fixture.service.executeMarketInsight("WF-1", "ART-KYC");

        ArgumentCaptor<AgentExecutionRequest> request = ArgumentCaptor.forClass(AgentExecutionRequest.class);
        verify(fixture.marketExecutor).execute(request.capture());
        assertThat(request.getValue().input()).isInstanceOf(com.privatebank.agent.domain.downstream.MarketInsightInput.class);
        assertThat(((com.privatebank.agent.domain.downstream.MarketInsightInput) request.getValue().input()).kycArtifactId())
                .isEqualTo("ART-KYC");
        verify(fixture.stateService).complete(eq(claim), anyString(), isNull());
    }

    @Test
    void executesProductExpertWithKycOnlyAndLetsAgentSearchProducts() {
        Fixture fixture = fixture();
        AgentExecutionClaim claim = claim(AgentType.PRODUCT_EXPERT);
        when(fixture.stateService.claim("WF-1", AgentType.PRODUCT_EXPERT)).thenReturn(Optional.of(claim));
        when(fixture.artifactMapper.selectById("ART-KYC")).thenReturn(artifact(
                "ART-KYC", AgentType.CUSTOMER_INSIGHT, "{\"analysis\":{}}"));
        when(fixture.productExecutor.execute(any())).thenReturn(new AgentExecutionResult<>(
                new KypRecommendationResult("KYP", "C-1", "ART-KYC", List.of(), List.of(), List.of(), List.of(),
                        List.of(), List.of()),
                1, "model"));

        fixture.service.executeProductExpert("WF-1", "ART-KYC");

        ArgumentCaptor<AgentExecutionRequest> request = ArgumentCaptor.forClass(AgentExecutionRequest.class);
        verify(fixture.productExecutor).execute(request.capture());
        Object input = request.getValue().input();
        assertThat(input).isInstanceOf(com.privatebank.agent.domain.downstream.ProductExpertInput.class);
        com.privatebank.agent.domain.downstream.ProductExpertInput productInput =
                (com.privatebank.agent.domain.downstream.ProductExpertInput) input;
        assertThat(productInput.candidateProductIds()).isEmpty();
        assertThat(productInput.productKnowledge()).isEmpty();
        verify(fixture.stateService).complete(eq(claim), anyString(), isNull());
    }

    @Test
    void executesCfsDesignOnlyAfterValidatingItsStructuredResult() {
        Fixture fixture = fixture();
        AgentExecutionClaim claim = claim(AgentType.SOLUTION_DESIGN);
        when(fixture.stateService.claim("WF-1", AgentType.SOLUTION_DESIGN)).thenReturn(Optional.of(claim));
        when(fixture.artifactMapper.selectById("ART-KYC")).thenReturn(artifact(
                "ART-KYC", AgentType.CUSTOMER_INSIGHT, "kyc"));
        when(fixture.artifactMapper.selectById("ART-MARKET")).thenReturn(artifact(
                "ART-MARKET", AgentType.MARKET_INSIGHT, "market"));
        when(fixture.artifactMapper.selectById("ART-KYP")).thenReturn(artifact(
                "ART-KYP", AgentType.PRODUCT_EXPERT, "kyp"));
        CfsDesignResult result = validCfs();
        when(fixture.cfsExecutor.execute(any())).thenReturn(new AgentExecutionResult<>(result, 1, "model"));

        fixture.service.executeCfsDesign("WF-1", "ART-KYC", "ART-MARKET", "ART-KYP");

        verify(fixture.cfsValidator).validate(result);
        verify(fixture.stateService).complete(eq(claim), anyString(), isNull());
    }

    @Test
    void executesComplianceCheckAndPersistsItsDecision() {
        Fixture fixture = fixture();
        AgentExecutionClaim claim = claim(AgentType.COMPLIANCE_CHECK);
        when(fixture.stateService.claim("WF-1", AgentType.COMPLIANCE_CHECK)).thenReturn(Optional.of(claim));
        when(fixture.artifactMapper.selectById("ART-CFS")).thenReturn(artifact(
                "ART-CFS", AgentType.SOLUTION_DESIGN, "{\"cfsVersion\":1}"));
        ComplianceCheckResult result = new ComplianceCheckResult(
                "ART-CFS", "PASS", "passed", List.of(), List.of(), List.of(), List.of());
        when(fixture.complianceExecutor.execute(any())).thenReturn(new AgentExecutionResult<>(result, 1, "model"));

        fixture.service.executeComplianceCheck("WF-1", "ART-CFS");

        verify(fixture.stateService).complete(eq(claim), anyString(), eq("PASS"));
    }

    @Test
    void marksExecutionFailedWhenAnInputArtifactIsMissing() {
        Fixture fixture = fixture();
        AgentExecutionClaim claim = claim(AgentType.MARKET_INSIGHT);
        when(fixture.stateService.claim("WF-1", AgentType.MARKET_INSIGHT)).thenReturn(Optional.of(claim));
        when(fixture.artifactMapper.selectById("ART-MISSING")).thenReturn(null);

        fixture.service.executeMarketInsight("WF-1", "ART-MISSING");

        verify(fixture.stateService).fail(eq(claim), eq("MARKET_INSIGHT_EXECUTION_FAILED"), anyString());
    }

    private Fixture fixture() {
        AgentWorkflowStateService stateService = mock(AgentWorkflowStateService.class);
        AgentArtifactMapper artifactMapper = mock(AgentArtifactMapper.class);
        MarketInsightAgentExecutor marketExecutor = mock(MarketInsightAgentExecutor.class);
        ProductExpertAgentExecutor productExecutor = mock(ProductExpertAgentExecutor.class);
        CfsDesignAgentExecutor cfsExecutor = mock(CfsDesignAgentExecutor.class);
        CfsDesignResultValidator cfsValidator = mock(CfsDesignResultValidator.class);
        ComplianceCheckAgentExecutor complianceExecutor = mock(ComplianceCheckAgentExecutor.class);
        return new Fixture(
                stateService, artifactMapper, marketExecutor, productExecutor, cfsExecutor,
                cfsValidator, complianceExecutor,
                new DownstreamAgentExecutionService(
                        stateService, artifactMapper, marketExecutor, productExecutor, cfsExecutor,
                        cfsValidator, complianceExecutor,
                        new ObjectMapper().findAndRegisterModules()));
    }

    private AgentExecutionClaim claim(AgentType type) {
        return new AgentExecutionClaim("WF-1", "AS-" + type.name(), type, "EXE-" + type.name(), "USER-1");
    }

    private AgentArtifact artifact(String id, AgentType type, String result) {
        AgentArtifact artifact = new AgentArtifact();
        artifact.setArtifactId(id);
        artifact.setWorkflowId("WF-1");
        artifact.setAgentType(type);
        artifact.setResult(result);
        return artifact;
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
            AgentWorkflowStateService stateService,
            AgentArtifactMapper artifactMapper,
            MarketInsightAgentExecutor marketExecutor,
            ProductExpertAgentExecutor productExecutor,
            CfsDesignAgentExecutor cfsExecutor,
            CfsDesignResultValidator cfsValidator,
            ComplianceCheckAgentExecutor complianceExecutor,
            DownstreamAgentExecutionService service) {
    }
}
