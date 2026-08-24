package com.privatebank.business.dto.admin;

public record AdminWorkflowDeleteResponse(
        String workflowId,
        boolean deleted,
        int deletedAgentStates,
        int deletedArtifacts,
        int deletedReviews,
        String fileCleanup) {
}
