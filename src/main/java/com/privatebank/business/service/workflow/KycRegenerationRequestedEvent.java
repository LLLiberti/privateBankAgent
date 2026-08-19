package com.privatebank.business.service.workflow;

import com.privatebank.agent.domain.kyc.KycQaItem;

import java.util.List;

/**
 * Published in the transaction that makes the KYC Agent ready for a new run.
 * Manager supplement fields are runtime-only. The KYC masking boundary must
 * redact them before model invocation and must not persist the original values.
 * qaItems carry sanitized/previous plus new Q&A context for multi-turn follow-up.
 */
public record KycRegenerationRequestedEvent(
        String workflowId,
        String managerDescription,
        List<String> managerConfirmedItems,
        List<KycQaItem> qaItems) {

    public KycRegenerationRequestedEvent {
        managerConfirmedItems = managerConfirmedItems == null ? List.of() : List.copyOf(managerConfirmedItems);
        qaItems = qaItems == null ? List.of() : List.copyOf(qaItems);
    }

    public KycRegenerationRequestedEvent(
            String workflowId,
            String managerDescription,
            List<String> managerConfirmedItems) {
        this(workflowId, managerDescription, managerConfirmedItems, List.of());
    }
}
