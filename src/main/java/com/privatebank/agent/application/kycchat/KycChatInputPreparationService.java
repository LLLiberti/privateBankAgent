package com.privatebank.agent.application.kycchat;

import com.privatebank.agent.application.kyc.KycDataMaskingService;
import com.privatebank.agent.application.kyc.KycRuntimeSupplement;
import com.privatebank.agent.domain.kyc.KycCustomerData;
import com.privatebank.agent.domain.kyc.KycMaskedInput;
import com.privatebank.agent.infrastructure.kyc.KycCustomerDataLoader;
import com.privatebank.business.common.exception.BusinessException;
import com.privatebank.business.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/** Prepares only masked, alias-normalized input for one KYC chat Agent turn. */
@Service
@RequiredArgsConstructor
public class KycChatInputPreparationService {

    public static final String SAME_AS_KYC_INPUT = "SAME_AS_KYC_INPUT";
    public static final String CURRENT_DATA_CHANGED_SINCE_KYC = "CURRENT_DATA_CHANGED_SINCE_KYC";

    private final KycCustomerDataLoader customerDataLoader;
    private final KycDataMaskingService dataMaskingService;
    private final KycChatAliasNormalizer aliasNormalizer;

    public KycChatPreparedTurn prepareTurn(
            KycChatContext context,
            String managerMessage,
            Map<String, String> canonicalMappings) {
        KycCustomerData customerData = customerDataLoader.load(context.personId());
        KycMaskedInput currentSnapshot = dataMaskingService.mask(customerData);
        KycMaskedInput messageInput = dataMaskingService.mask(
                customerData, new KycRuntimeSupplement(managerMessage, List.of()));
        Object maskedMessage = messageInput.payload().get("managerInstruction");
        if (!(maskedMessage instanceof String text) || !StringUtils.hasText(text)) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST, ErrorCode.INVALID_ARGUMENT, "客户经理消息脱敏后为空");
        }

        KycChatAliasNormalizer.AliasPlan plan = aliasNormalizer.plan(
                messageInput.aliasMappings(), canonicalMappings);
        String comparison = context.maskedInputSha256().equals(currentSnapshot.sha256())
                ? SAME_AS_KYC_INPUT
                : CURRENT_DATA_CHANGED_SINCE_KYC;
        return new KycChatPreparedTurn(
                aliasNormalizer.normalizeText(text, plan),
                aliasNormalizer.normalizePayload(currentSnapshot.payload(), plan),
                comparison,
                plan.canonicalMappings());
    }
}
