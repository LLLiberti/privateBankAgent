package com.privatebank.agent.application.kyc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.agent.application.runtime.AgentExecutionCompletedEvent;
import com.privatebank.agent.application.runtime.AgentExecutionFailedEvent;
import com.privatebank.agent.application.runtime.AgentExecutionRequestedEvent;
import com.privatebank.agent.application.runtime.AgentExecutionResult;
import com.privatebank.agent.application.runtime.AgentRuntimeException;
import com.privatebank.agent.domain.kyc.KycCustomerData;
import com.privatebank.agent.domain.kyc.KycGenerationException;
import com.privatebank.agent.domain.kyc.KycGenerationResult;
import com.privatebank.agent.domain.kyc.KycMaskedInput;
import com.privatebank.agent.domain.kyc.KycOutputValidationException;
import com.privatebank.agent.domain.kyc.KycStructuredResult;
import com.privatebank.agent.infrastructure.kyc.KycCustomerDataLoader;
import com.privatebank.business.dto.customer.CustomerSummaryResponse;
import com.privatebank.business.enums.workflow.AgentType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KycWorkflowExecutionServiceTest {

    @Test
    void publishesCompletedResultAfterExecutingConcreteKycCapability() throws Exception {
        Fixture fixture = fixture();
        when(fixture.executor.execute(any())).thenReturn(
                new AgentExecutionResult<>(structuredResult(), 2, "fake-deepseek"));
        when(fixture.executor.toGenerationResult(any())).thenReturn(
                new KycGenerationResult(validResult(), 2, "fake-deepseek"));

        fixture.service.execute(fixture.event);

        verify(fixture.loader).load(100L);
        verify(fixture.maskingService).mask(eq(fixture.data), eq(KycRuntimeSupplement.empty()));
        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(fixture.publisher).publishEvent(published.capture());
        assertThat(published.getValue()).isInstanceOf(AgentExecutionCompletedEvent.class);
        AgentExecutionCompletedEvent completed = (AgentExecutionCompletedEvent) published.getValue();
        assertThat(completed.executionId()).isEqualTo("EXE-1");
        assertThat(completed.retryCountIncrement()).isEqualTo(1);
        JsonNode saved = fixture.objectMapper.readTree(completed.resultJson());
        assertThat(saved.path("contractVersion").asText()).isEqualTo("kyc-result.v2");
        assertThat(saved.path("maskedInputSha256").asText()).isEqualTo("b".repeat(64));
        assertThat(saved.path("analysis").path("summary").asText()).isEqualTo("ok");
    }

    @Test
    void publishesContractFailureWhenBusinessValidationCannotBeRepaired() {
        Fixture fixture = fixture();
        when(fixture.executor.execute(any())).thenThrow(new KycGenerationException(
                "连续返回不符合业务约束的结果",
                new KycOutputValidationException("graphAssessment 引用了非 Neo4j 关系证据")));

        fixture.service.execute(fixture.event);

        AgentExecutionFailedEvent failed = publishedFailure(fixture);
        assertThat(failed.errorCode()).isEqualTo("KYC_OUTPUT_CONTRACT_INVALID");
        assertThat(failed.errorMessage()).contains("graphAssessment 引用了非 Neo4j 关系证据");
    }

    @Test
    void publishesModelFailureWhenRuntimeFails() {
        Fixture fixture = fixture();
        when(fixture.executor.execute(any())).thenThrow(new AgentRuntimeException("model unavailable"));

        fixture.service.execute(fixture.event);

        AgentExecutionFailedEvent failed = publishedFailure(fixture);
        assertThat(failed.errorCode()).isEqualTo("KYC_MODEL_CALL_FAILED");
        assertThat(failed.errorMessage()).isEqualTo("KYC 模型调用失败，请稍后重试");
    }

    private AgentExecutionFailedEvent publishedFailure(Fixture fixture) {
        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(fixture.publisher).publishEvent(published.capture());
        assertThat(published.getValue()).isInstanceOf(AgentExecutionFailedEvent.class);
        return (AgentExecutionFailedEvent) published.getValue();
    }

    private Fixture fixture() {
        KycCustomerDataLoader loader = mock(KycCustomerDataLoader.class);
        KycDataMaskingService maskingService = mock(KycDataMaskingService.class);
        KycAgentExecutor executor = mock(KycAgentExecutor.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        KycCustomerData data = emptyData();
        KycMaskedInput input = new KycMaskedInput(
                Map.of("person", Map.of()), Map.of(), Set.of(), "b".repeat(64));
        when(loader.load(100L)).thenReturn(data);
        when(maskingService.mask(eq(data), eq(KycRuntimeSupplement.empty()))).thenReturn(input);
        AgentExecutionRequestedEvent event = new AgentExecutionRequestedEvent(
                "WF-1", "AS-CUSTOMER_INSIGHT", AgentType.CUSTOMER_INSIGHT, "EXE-1",
                "USER-1", 100L, Map.of(), Map.of(), null, List.of(), List.of());
        return new Fixture(loader, maskingService, executor, publisher, objectMapper, data, input, event,
                new KycWorkflowExecutionService(loader, maskingService, executor, objectMapper, publisher));
    }

    private KycCustomerData emptyData() {
        return new KycCustomerData(
                new CustomerSummaryResponse(1L, "张三", null, "ENTREPRENEUR", "VERIFIED", "UNKNOWN"),
                Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
    }

    private String validResult() {
        return "{\"riskLevel\":\"LOW\",\"summary\":\"ok\",\"findings\":[],\"riskAlerts\":[],"
                + "\"recommendedActions\":[],\"dataGaps\":[],\"graphAssessment\":"
                + "{\"contribution\":\"NOT_AVAILABLE\",\"summary\":\"no graph\",\"evidenceRefs\":[]}}";
    }

    private KycStructuredResult structuredResult() {
        return new KycStructuredResult(
                KycStructuredResult.RiskLevel.LOW, "ok", List.of(), List.of(), List.of(), List.of(),
                new KycStructuredResult.GraphAssessment(
                        KycStructuredResult.GraphContribution.NOT_AVAILABLE, "no graph", List.of()));
    }

    private record Fixture(
            KycCustomerDataLoader loader,
            KycDataMaskingService maskingService,
            KycAgentExecutor executor,
            ApplicationEventPublisher publisher,
            ObjectMapper objectMapper,
            KycCustomerData data,
            KycMaskedInput input,
            AgentExecutionRequestedEvent event,
            KycWorkflowExecutionService service) {
    }
}
