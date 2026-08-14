package com.privatebank.business.service.workflow;

import com.privatebank.business.enums.workflow.AgentType;

import java.util.List;

/**
 * Signals that the customer manager has approved the current KYC artifact and
 * that downstream agents may start their own work.
 */
public record DownstreamAgentsReadyEvent(
        String workflowId,
        String kycArtifactId,
        List<AgentType> agentTypes) {
}
