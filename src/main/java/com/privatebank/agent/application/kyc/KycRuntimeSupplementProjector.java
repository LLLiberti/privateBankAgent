package com.privatebank.agent.application.kyc;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;

/**
 * Runs only while the request event is in memory. It turns manager free text
 * into controlled signal codes and never returns or writes raw text.
 */
@Component
public class KycRuntimeSupplementProjector {

    private final KycSemanticProjectionService semanticProjectionService = new KycSemanticProjectionService();

    public KycRuntimeSupplement project(String description, List<String> confirmedItems) {
        if (!StringUtils.hasText(description) && (confirmedItems == null || confirmedItems.isEmpty())) {
            return KycRuntimeSupplement.empty();
        }
        String[] source = source(description, confirmedItems);
        Set<String> signals = semanticProjectionService.managerSupplementSignals(source);
        return new KycRuntimeSupplement(signals);
    }

    private String[] source(String description, List<String> confirmedItems) {
        int itemCount = confirmedItems == null ? 0 : confirmedItems.size();
        String[] source = new String[itemCount + 1];
        source[0] = description;
        for (int index = 0; index < itemCount; index++) {
            source[index + 1] = confirmedItems.get(index);
        }
        return source;
    }
}
