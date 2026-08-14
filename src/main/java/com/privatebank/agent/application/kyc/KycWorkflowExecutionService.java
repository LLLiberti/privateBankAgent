package com.privatebank.agent.application.kyc;

import com.privatebank.agent.application.runtime.AgentExecutionRequest;
import com.privatebank.agent.application.runtime.AgentExecutionResult;
import com.privatebank.agent.application.runtime.AgentRuntimeException;
import com.privatebank.agent.domain.kyc.KycGenerationException;
import com.privatebank.agent.domain.kyc.KycGenerationResult;
import com.privatebank.agent.domain.kyc.KycInputValidationException;
import com.privatebank.agent.domain.kyc.KycMaskedInput;
import com.privatebank.agent.domain.kyc.KycOutputValidationException;
import com.privatebank.agent.domain.kyc.KycStructuredResult;
import com.privatebank.agent.infrastructure.kyc.KycCustomerDataLoader;
import com.privatebank.agent.infrastructure.kyc.KycWorkflowStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class KycWorkflowExecutionService {

    private static final String OUTPUT_CONTRACT_ERROR = "KYC_OUTPUT_CONTRACT_INVALID";
    private static final String INPUT_CONTRACT_ERROR = "KYC_MASKED_INPUT_INVALID";
    private static final String MODEL_CALL_ERROR = "KYC_MODEL_CALL_FAILED";

    private final KycWorkflowStateService workflowStateService;
    private final KycCustomerDataLoader customerDataLoader;
    private final KycDataMaskingService dataMaskingService;
    private final KycAgentExecutor kycAgentExecutor;

    public void execute(String workflowId) {
        execute(workflowId, KycRuntimeSupplement.empty());
    }

    public void execute(String workflowId, KycRuntimeSupplement supplement) {
        Optional<KycExecutionClaim> optionalClaim = workflowStateService.claim(workflowId);
        if (optionalClaim.isEmpty()) {
            return;
        }
        KycExecutionClaim claim = optionalClaim.get();
        KycMaskedInput input;
        KycGenerationResult result;
        try {
            input = dataMaskingService.mask(customerDataLoader.load(claim.personId()), supplement);
            AgentExecutionResult<KycStructuredResult> executionResult = kycAgentExecutor.execute(
                    new AgentExecutionRequest<>(
                            claim.workflowId(),
                            claim.executionId(),
                            kycAgentExecutor.agentType(),
                            claim.operatorUserId(),
                            input,
                            Map.of("personId", claim.personId())));
            result = kycAgentExecutor.toGenerationResult(executionResult);
        } catch (KycInputValidationException exception) {
            workflowStateService.fail(claim, INPUT_CONTRACT_ERROR, "KYC 脱敏输入未通过出站安全校验");
            return;
        } catch (KycGenerationException exception) {
            String validationFailure = validationFailure(exception);
            log.warn("KYC output contract validation failed for workflow {} executionId={}: {}",
                    workflowId, claim.executionId(), validationFailure);
            workflowStateService.fail(claim, OUTPUT_CONTRACT_ERROR,
                    "KYC 分析结果未通过证据、格式或脱敏校验：" + validationFailure);
            return;
        } catch (AgentRuntimeException exception) {
            log.warn("KYC AgentScope execution failed for workflow {} executionId={}: rootCauseType={}",
                    workflowId, claim.executionId(), rootCause(exception).getClass().getSimpleName());
            workflowStateService.fail(claim, MODEL_CALL_ERROR, "KYC 模型调用失败，请稍后重试");
            return;
        } catch (RuntimeException exception) {
            log.warn("KYC preparation failed for workflow {} executionId={}: failureType=PREPARATION_FAILED rootCauseType={}",
                    workflowId, claim.executionId(), rootCause(exception).getClass().getSimpleName(), exception);
            throw exception;
        }
        workflowStateService.complete(claim, input, result);
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
