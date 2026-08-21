package com.privatebank.agent.application.downstream;

import com.privatebank.agent.application.kyc.KycDataMaskingService;
import com.privatebank.agent.domain.kyc.KycCustomerData;
import com.privatebank.agent.domain.kyc.KycMaskedInput;
import com.privatebank.agent.infrastructure.kyc.KycCustomerDataLoader;
import com.privatebank.business.entity.workflow.WorkflowState;
import com.privatebank.business.mapper.workflow.WorkflowStateMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * AgentScope-visible tool for CFS/other downstream agents.
 *
 * <p>It always returns the same masked customer payload that KYC uses, so raw
 * customer data never crosses the model boundary.  The tool is intentionally
 * on-demand: the agent calls it only when it needs customer information.</p>
 */
@Component
@RequiredArgsConstructor
public class CustomerProfileTool {

    private final KycCustomerDataLoader customerDataLoader;
    private final KycDataMaskingService dataMaskingService;
    private final WorkflowStateMapper workflowStateMapper;

    public Map<String, Object> getCustomerProfile(String workflowId) {
        WorkflowState workflow = workflowStateMapper.selectById(workflowId);
        if (workflow == null) {
            throw new IllegalStateException("工作流不存在: " + workflowId);
        }
        KycCustomerData customerData = customerDataLoader.load(workflow.getPersonId());
        KycMaskedInput maskedInput = dataMaskingService.mask(customerData);
        return maskedInput.payload();
    }
}
