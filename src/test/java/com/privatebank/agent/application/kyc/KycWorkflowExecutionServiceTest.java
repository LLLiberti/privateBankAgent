package com.privatebank.agent.application.kyc;

import com.privatebank.agent.domain.kyc.KycCustomerData;
import com.privatebank.agent.domain.kyc.KycGenerationException;
import com.privatebank.agent.domain.kyc.KycGenerationResult;
import com.privatebank.agent.domain.kyc.KycMaskedInput;
import com.privatebank.agent.infrastructure.kyc.KycCustomerDataLoader;
import com.privatebank.agent.infrastructure.kyc.KycWorkflowStateService;
import com.privatebank.business.dto.customer.CustomerSummaryResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KycWorkflowExecutionServiceTest {

    @Test
    void completesClaimedWorkflowWithMaskedInputAndValidatedAnalysis() {
        KycWorkflowStateService stateService = mock(KycWorkflowStateService.class);
        KycCustomerDataLoader loader = mock(KycCustomerDataLoader.class);
        KycDataMaskingService maskingService = mock(KycDataMaskingService.class);
        KycAnalysisGenerator generator = mock(KycAnalysisGenerator.class);
        KycWorkflowExecutionService service = new KycWorkflowExecutionService(
                stateService, loader, maskingService, generator);
        KycExecutionClaim claim = new KycExecutionClaim("WF-1", 100L, "EXE-1");
        KycCustomerData data = emptyData();
        KycMaskedInput input = new KycMaskedInput(Map.of("person", Map.of()), Map.of(), Set.of(), "b".repeat(64));
        KycGenerationResult result = new KycGenerationResult(validResult(), 1, "fake-deepseek");
        when(stateService.claim("WF-1")).thenReturn(Optional.of(claim));
        when(loader.load(100L)).thenReturn(data);
        when(maskingService.mask(eq(data), eq(KycRuntimeSupplement.empty()))).thenReturn(input);
        when(generator.generate(input)).thenReturn(result);

        service.execute("WF-1");

        verify(stateService).complete(eq(claim), eq(input), eq(result));
    }

    @Test
    void marksWorkflowFailedWhenAllGeneratedOutputsAreInvalid() {
        KycWorkflowStateService stateService = mock(KycWorkflowStateService.class);
        KycCustomerDataLoader loader = mock(KycCustomerDataLoader.class);
        KycDataMaskingService maskingService = mock(KycDataMaskingService.class);
        KycAnalysisGenerator generator = mock(KycAnalysisGenerator.class);
        KycWorkflowExecutionService service = new KycWorkflowExecutionService(
                stateService, loader, maskingService, generator);
        KycExecutionClaim claim = new KycExecutionClaim("WF-2", 101L, "EXE-2");
        KycCustomerData data = emptyData();
        KycMaskedInput input = new KycMaskedInput(Map.of("person", Map.of()), Map.of(), Set.of(), "c".repeat(64));
        when(stateService.claim("WF-2")).thenReturn(Optional.of(claim));
        when(loader.load(101L)).thenReturn(data);
        when(maskingService.mask(eq(data), eq(KycRuntimeSupplement.empty()))).thenReturn(input);
        when(generator.generate(input)).thenThrow(new KycGenerationException("格式错误", null));

        service.execute("WF-2");

        verify(stateService).fail(eq(claim), eq("KYC_OUTPUT_CONTRACT_INVALID"), eq("KYC 分析结果未通过格式或脱敏校验"));
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
}
