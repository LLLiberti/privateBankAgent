package com.privatebank.business.service.workflow;

import java.util.List;

/**
 * Published in the transaction that makes the KYC Agent ready for a new run.
 * Manager supplement fields are runtime-only. The listener must project them
 * before the model boundary and must not persist the original values.
 */
public record KycRegenerationRequestedEvent(
        String workflowId,
        String managerDescription,
        List<String> managerConfirmedItems) {

    public KycRegenerationRequestedEvent {
        managerConfirmedItems = managerConfirmedItems == null ? List.of() : List.copyOf(managerConfirmedItems);
    }
}
