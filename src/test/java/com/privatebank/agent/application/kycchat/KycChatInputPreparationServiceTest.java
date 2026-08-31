package com.privatebank.agent.application.kycchat;

import com.privatebank.agent.application.kyc.KycDataMaskingService;
import com.privatebank.agent.application.kyc.KycRuntimeSupplement;
import com.privatebank.agent.domain.kyc.KycCustomerData;
import com.privatebank.agent.domain.kyc.KycMaskedInput;
import com.privatebank.agent.infrastructure.kyc.KycCustomerDataLoader;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KycChatInputPreparationServiceTest {

    @Test
    void preparesManagerMessageAndCurrentDataWithPersistedAliasBaseline() {
        KycCustomerDataLoader customerDataLoader = mock(KycCustomerDataLoader.class);
        KycDataMaskingService dataMaskingService = mock(KycDataMaskingService.class);
        KycChatInputPreparationService service = new KycChatInputPreparationService(
                customerDataLoader, dataMaskingService, new KycChatAliasNormalizer());
        KycCustomerData customerData = mock(KycCustomerData.class);
        KycMaskedInput snapshot = new KycMaskedInput(
                Map.of("person", Map.of("personAlias", "P-1"),
                        "enterprise", Map.of("enterpriseAlias", "E-1")),
                Map.of(), Set.of(), Map.of("P-1", "张三", "E-1", "新增企业"), "new-hash");
        Map<String, String> messageMappings = new LinkedHashMap<>();
        messageMappings.put("P-1", "张三");
        messageMappings.put("E-1", "新增企业");
        messageMappings.put("E-2", "补充企业");
        KycMaskedInput messageInput = new KycMaskedInput(
                Map.of("managerInstruction", "请核对P-1、E-1与E-2"),
                Map.of(), Set.of(), messageMappings, "message-hash");
        when(customerDataLoader.load(1001L)).thenReturn(customerData);
        when(dataMaskingService.mask(customerData)).thenReturn(snapshot);
        when(dataMaskingService.mask(eq(customerData), any(KycRuntimeSupplement.class)))
                .thenReturn(messageInput);
        KycChatContext context = new KycChatContext(
                "WF-1", 1001L, "ART-1", "{}", "old-hash",
                Map.of("P-1", "张三", "E-1", "原有企业"));

        KycChatPreparedTurn prepared = service.prepareTurn(
                context, "请核对张三与新增企业", context.aliasMappings());

        assertThat(prepared.maskedMessage()).isEqualTo("请核对P-1、E-2与E-3");
        assertThat(prepared.currentMaskedData().toString()).contains("enterpriseAlias=E-2");
        assertThat(prepared.snapshotComparison())
                .isEqualTo(KycChatInputPreparationService.CURRENT_DATA_CHANGED_SINCE_KYC);
        assertThat(prepared.aliasMappings())
                .containsEntry("E-1", "原有企业")
                .containsEntry("E-2", "新增企业")
                .containsEntry("E-3", "补充企业");
    }
}
