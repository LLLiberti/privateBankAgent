package com.privatebank.agent.application.kyc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.agent.application.runtime.AgentExecutionCompletedEvent;
import com.privatebank.agent.application.runtime.AgentExecutionFailedEvent;
import com.privatebank.agent.application.runtime.AgentExecutionRequest;
import com.privatebank.agent.application.runtime.AgentExecutionRequestedEvent;
import com.privatebank.agent.application.runtime.AgentExecutionResult;
import com.privatebank.agent.application.runtime.AgentRuntimeException;
import com.privatebank.agent.domain.kyc.KycGenerationException;
import com.privatebank.agent.domain.kyc.KycGenerationResult;
import com.privatebank.agent.domain.kyc.KycInputValidationException;
import com.privatebank.agent.domain.kyc.KycMaskedInput;
import com.privatebank.agent.domain.kyc.KycOutputValidationException;
import com.privatebank.agent.domain.kyc.KycStructuredResult;
import com.privatebank.agent.infrastructure.kyc.KycCustomerDataLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class KycWorkflowExecutionService {

    private static final String OUTPUT_CONTRACT_ERROR = "KYC_OUTPUT_CONTRACT_INVALID";
    private static final String INPUT_CONTRACT_ERROR = "KYC_MASKED_INPUT_INVALID";
    private static final String MODEL_CALL_ERROR = "KYC_MODEL_CALL_FAILED";
    private static final String PREPARATION_ERROR = "KYC_PREPARATION_FAILED";

    private final KycCustomerDataLoader customerDataLoader;
    private final KycDataMaskingService dataMaskingService;
    private final KycAgentExecutor kycAgentExecutor;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    public void execute(AgentExecutionRequestedEvent event) {
        KycMaskedInput input;
        KycGenerationResult result;
        try {
            KycRuntimeSupplement supplement = new KycRuntimeSupplement(
                    event.managerDescription(),
                    event.managerConfirmedItems(),
                    event.qaItems());
            input = dataMaskingService.mask(customerDataLoader.load(event.personId()), supplement);
            AgentExecutionResult<KycStructuredResult> executionResult = kycAgentExecutor.execute(
                    new AgentExecutionRequest<>(
                            event.workflowId(),
                            event.executionId(),
                            event.agentType(),
                            event.operatorUserId(),
                            input,
                            Map.of("personId", event.personId())));
            result = kycAgentExecutor.toGenerationResult(executionResult);
        } catch (KycInputValidationException exception) {
            fail(event, INPUT_CONTRACT_ERROR, "KYC 脱敏输入未通过出站安全校验");
            return;
        } catch (KycGenerationException exception) {
            String validationFailure = validationFailure(exception);
            log.warn("KYC output contract validation failed for workflow {} executionId={}: {}",
                    event.workflowId(), event.executionId(), validationFailure);
            fail(event, OUTPUT_CONTRACT_ERROR, "KYC 分析结果未通过证据、格式或脱敏校验：" + validationFailure);
            return;
        } catch (AgentRuntimeException exception) {
            log.warn("KYC AgentScope execution failed for workflow {} executionId={}: rootCauseType={}",
                    event.workflowId(), event.executionId(), rootCause(exception).getClass().getSimpleName());
            fail(event, MODEL_CALL_ERROR, "KYC 模型调用失败，请稍后重试");
            return;
        } catch (RuntimeException exception) {
            log.warn("KYC preparation failed for workflow {} executionId={}: rootCauseType={}",
                    event.workflowId(), event.executionId(), rootCause(exception).getClass().getSimpleName(), exception);
            fail(event, PREPARATION_ERROR, "KYC 数据准备失败，请稍后重试");
            return;
        }
        eventPublisher.publishEvent(new AgentExecutionCompletedEvent(
                event.workflowId(),
                event.agentStateId(),
                event.agentType(),
                event.executionId(),
                artifactResult(input, result),
                null,
                Math.max(0, result.attempts() - 1)));
    }

    private String artifactResult(KycMaskedInput input, KycGenerationResult result) {
        try {
            JsonNode analysis = objectMapper.readTree(result.analysisJson());
            Map<String, Object> saved = new LinkedHashMap<>();
            saved.put("contractVersion", "kyc-result.v2");
            saved.put("model", result.modelName());
            saved.put("modelAttempts", result.attempts());
            saved.put("maskingApplied", true);
            saved.put("maskedInputSha256", input.sha256());
            saved.put("evidenceReferences", input.evidenceReferences());
            saved.put("aliasMappings", input.aliasMappings());
            saved.put("analysis", analysis);
            Object qaHistory = input.payload().get("managerQa");
            if (qaHistory != null) {
                saved.put("qaHistory", qaHistory);
            }
            return objectMapper.writeValueAsString(saved);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("KYC 分析结果无法保存", exception);
        }
    }

    private void fail(AgentExecutionRequestedEvent event, String errorCode, String errorMessage) {
        eventPublisher.publishEvent(new AgentExecutionFailedEvent(
                event.workflowId(),
                event.agentStateId(),
                event.agentType(),
                event.executionId(),
                errorCode,
                errorMessage));
    }

    private Throwable rootCause(Throwable exception) {
        Throwable current = exception;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private String validationFailure(KycGenerationException exception) {
        Throwable cause = rootCause(exception);
        return cause instanceof KycOutputValidationException && cause.getMessage() != null
                ? cause.getMessage()
                : "未知输出校验错误";
    }
}
