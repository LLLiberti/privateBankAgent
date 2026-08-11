package com.privatebank.agent.application.kyc;

import com.privatebank.agent.domain.kyc.KycGenerationException;
import com.privatebank.agent.domain.kyc.KycGenerationResult;
import com.privatebank.agent.domain.kyc.KycInputValidationException;
import com.privatebank.agent.domain.kyc.KycMaskedInput;
import com.privatebank.agent.infrastructure.kyc.KycCustomerDataLoader;
import com.privatebank.agent.infrastructure.kyc.KycWorkflowStateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class KycWorkflowExecutionService {

    private static final String OUTPUT_CONTRACT_ERROR = "KYC_OUTPUT_CONTRACT_INVALID";
    private static final String INPUT_CONTRACT_ERROR = "KYC_MASKED_INPUT_INVALID";
    private static final String MODEL_CALL_ERROR = "KYC_MODEL_CALL_FAILED";

    private final KycWorkflowStateService workflowStateService;
    private final KycCustomerDataLoader customerDataLoader;
    private final KycDataMaskingService dataMaskingService;
    private final KycAnalysisGenerator analysisGenerator;

    public void execute(String workflowId) {
        Optional<KycExecutionClaim> optionalClaim = workflowStateService.claim(workflowId);
        if (optionalClaim.isEmpty()) {
            return;
        }
        KycExecutionClaim claim = optionalClaim.get();
        try {
            KycMaskedInput input = dataMaskingService.mask(customerDataLoader.load(claim.personId()));
            KycGenerationResult result = analysisGenerator.generate(input);
            workflowStateService.complete(claim, input, result);
        } catch (KycInputValidationException exception) {
            workflowStateService.fail(claim, INPUT_CONTRACT_ERROR, "KYC 脱敏输入未通过出站安全校验");
        } catch (KycGenerationException exception) {
            workflowStateService.fail(claim, OUTPUT_CONTRACT_ERROR, "KYC 分析结果未通过格式或脱敏校验");
        } catch (RuntimeException exception) {
            workflowStateService.fail(claim, MODEL_CALL_ERROR, "KYC 模型调用失败，请稍后重试");
        }
    }
}
