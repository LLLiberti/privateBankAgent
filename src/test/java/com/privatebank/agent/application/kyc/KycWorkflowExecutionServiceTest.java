package com.privatebank.agent.application.kyc;

import com.privatebank.agent.application.runtime.AgentExecutionResult;
import com.privatebank.agent.application.runtime.AgentRuntimeException;
import com.privatebank.agent.domain.kyc.KycCustomerData;
import com.privatebank.agent.domain.kyc.KycGenerationException;
import com.privatebank.agent.domain.kyc.KycGenerationResult;
import com.privatebank.agent.domain.kyc.KycMaskedInput;
import com.privatebank.agent.domain.kyc.KycStructuredResult;
import com.privatebank.agent.infrastructure.kyc.KycCustomerDataLoader;
import com.privatebank.agent.infrastructure.kyc.KycWorkflowStateService;
import com.privatebank.business.dto.customer.CustomerSummaryResponse;
import com.privatebank.business.enums.workflow.AgentType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KycWorkflowExecutionServiceTest {

    @Test
    void completesClaimedWorkflowWithMaskedInputAndValidatedAnalysis() {
        Fixture fixture = fixture("WF-1", 100L, "EXE-1");
        KycGenerationResult result = new KycGenerationResult(validResult(), 1, "fake-deepseek");
        when(fixture.executor.execute(any())).thenReturn(
                new AgentExecutionResult<>(structuredResult(), 1, "fake-deepseek"));
        when(fixture.executor.toGenerationResult(any())).thenReturn(result);

        fixture.service.execute("WF-1");

        verify(fixture.stateService).complete(eq(fixture.claim), eq(fixture.input), eq(result));
    }

    @Test
    void marksWorkflowFailedWhenBusinessValidationCannotBeRepaired() {
        Fixture fixture = fixture("WF-2", 101L, "EXE-2");
        when(fixture.executor.execute(any())).thenThrow(new KycGenerationException("格式错误", null));

        fixture.service.execute("WF-2");

        verify(fixture.stateService).fail(
                eq(fixture.claim), eq("KYC_OUTPUT_CONTRACT_INVALID"),
                eq("KYC 分析结果未通过证据、格式或脱敏校验"));
    }

    @Test
    void marksWorkflowFailedWhenAgentScopeRuntimeFails() {
        Fixture fixture = fixture("WF-3", 102L, "EXE-3");
        when(fixture.executor.execute(any())).thenThrow(new AgentRuntimeException("model unavailable"));

        fixture.service.execute("WF-3");

        verify(fixture.stateService).fail(
                eq(fixture.claim), eq("KYC_MODEL_CALL_FAILED"), eq("KYC 模型调用失败，请稍后重试"));
    }

    @Test
    void doesNotOverwriteSuccessfulExecutionWhenCompletionSideEffectFails() {
        Fixture fixture = fixture("WF-4", 103L, "EXE-4");
        KycGenerationResult result = new KycGenerationResult(validResult(), 1, "fake-deepseek");
        when(fixture.executor.execute(any())).thenReturn(
                new AgentExecutionResult<>(structuredResult(), 1, "fake-deepseek"));
        when(fixture.executor.toGenerationResult(any())).thenReturn(result);
        doThrow(new IllegalStateException("success event failed"))
                .when(fixture.stateService).complete(eq(fixture.claim), eq(fixture.input), eq(result));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> fixture.service.execute("WF-4"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("success event failed");

        verify(fixture.stateService, never()).fail(
                eq(fixture.claim), eq("KYC_MODEL_CALL_FAILED"), org.mockito.ArgumentMatchers.anyString());
    }

    private Fixture fixture(String workflowId, long personId, String executionId) {
        KycWorkflowStateService stateService = mock(KycWorkflowStateService.class);
        KycCustomerDataLoader loader = mock(KycCustomerDataLoader.class);
        KycDataMaskingService maskingService = mock(KycDataMaskingService.class);
        KycAgentExecutor executor = mock(KycAgentExecutor.class);
        KycWorkflowExecutionService service = new KycWorkflowExecutionService(
                stateService, loader, maskingService, executor);
        KycExecutionClaim claim = new KycExecutionClaim(workflowId, personId, executionId, "USER-1");
        KycCustomerData data = emptyData();
        KycMaskedInput input = new KycMaskedInput(
                Map.of("person", Map.of()), Map.of(), Set.of(), "b".repeat(64));
        when(stateService.claim(workflowId)).thenReturn(Optional.of(claim));
        when(loader.load(personId)).thenReturn(data);
        when(maskingService.mask(eq(data), eq(KycRuntimeSupplement.empty()))).thenReturn(input);
        when(executor.agentType()).thenReturn(AgentType.CUSTOMER_INSIGHT);
        return new Fixture(stateService, executor, service, claim, input);
    }

    private KycCustomerData emptyData() {
        return new KycCustomerData(
                new CustomerSummaryResponse(1L, "张三", null, "ENTREPRENEUR", "VERIFIED", "UNKNOWN"),
                Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
    }

    private String validResult() {
        return "{\"riskLevel\":\"LOW\",\"summary\":\"ok\",\"findings\":[],\"riskAlerts\":[],\"recommendedActions\":[],\"dataGaps\":[]}";
    }

    private KycStructuredResult structuredResult() {
        return new KycStructuredResult(
                KycStructuredResult.RiskLevel.LOW, "ok", List.of(), List.of(), List.of(), List.of());
    }

    private record Fixture(
            KycWorkflowStateService stateService,
            KycAgentExecutor executor,
            KycWorkflowExecutionService service,
            KycExecutionClaim claim,
            KycMaskedInput input) {
    }
}
