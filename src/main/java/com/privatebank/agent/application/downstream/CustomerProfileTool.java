package com.privatebank.agent.application.downstream;

import com.privatebank.agent.application.kyc.KycDataMaskingService;
import com.privatebank.agent.domain.kyc.KycCustomerData;
import com.privatebank.agent.domain.kyc.KycMaskedInput;
import com.privatebank.agent.infrastructure.kyc.KycCustomerDataLoader;
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

    public BoundCustomerProfileTool bind(Long personId) {
        if (personId == null) {
            throw new IllegalStateException("Agent运行时缺少personId");
        }
        return new BoundCustomerProfileTool(personId);
    }

    public final class BoundCustomerProfileTool {
        private final Long personId;

        private BoundCustomerProfileTool(Long personId) {
            this.personId = personId;
        }

        public Map<String, Object> getCustomerProfile() {
            KycCustomerData customerData = customerDataLoader.load(personId);
            KycMaskedInput maskedInput = dataMaskingService.mask(customerData);
            return maskedInput.payload();
        }
    }
}
