package com.privatebank.agent.application.kyc;

import com.privatebank.agent.domain.kyc.KycGenerationException;
import com.privatebank.agent.domain.kyc.KycGenerationResult;
import com.privatebank.agent.domain.kyc.KycInputValidationException;
import com.privatebank.agent.domain.kyc.KycMaskedInput;
import com.privatebank.agent.domain.kyc.KycModelInvocationException;
import com.privatebank.agent.infrastructure.kyc.KycCustomerDataLoader;
import com.privatebank.agent.infrastructure.kyc.KycWorkflowStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
    private final KycAnalysisGenerator analysisGenerator;

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
            result = analysisGenerator.generate(input);
        } catch (KycInputValidationException exception) {
            workflowStateService.fail(claim, INPUT_CONTRACT_ERROR, "KYC 脱敏输入未通过出站安全校验");
            return;
        } catch (KycGenerationException exception) {
            workflowStateService.fail(claim, OUTPUT_CONTRACT_ERROR, "KYC 分析结果未通过格式或脱敏校验");
            return;
        } catch (KycModelInvocationException exception) {
            log.warn("KYC model invocation failed for workflow {} executionId={}: failureType={} rootCauseType={}",
                    workflowId, claim.executionId(), modelFailureType(exception),
                    rootCause(exception).getClass().getSimpleName());
            workflowStateService.fail(claim, MODEL_CALL_ERROR, "KYC 模型调用失败，请稍后重试");
            return;
        } catch (RuntimeException exception) {
            log.warn("KYC preparation failed for workflow {} executionId={}: failureType=PREPARATION_FAILED rootCauseType={}",
                    workflowId, claim.executionId(), rootCause(exception).getClass().getSimpleName(), exception);
            throw exception;
        }
        workflowStateService.complete(claim, input, result);
    }

    private String modelFailureType(KycModelInvocationException exception) {
        return "KYC 模型未返回可用内容".equals(exception.getMessage())
                ? "EMPTY_RESPONSE"
                : "INVOCATION_FAILED";
    }

    private Throwable rootCause(Throwable exception) {
        Throwable current = exception;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
