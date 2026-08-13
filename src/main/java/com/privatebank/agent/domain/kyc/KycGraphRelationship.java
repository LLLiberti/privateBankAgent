package com.privatebank.agent.domain.kyc;

/**
 * Relationship-only Neo4j projection used by KYC. Names and free text are deliberately
 * excluded so the graph cannot bypass the KYC masking boundary.
 */
public record KycGraphRelationship(
        String startNodeId,
        String startNodeType,
        boolean startIsCustomer,
        String relationType,
        String endNodeId,
        String endNodeType,
        boolean endIsCustomer,
        Long sourceId,
        String verificationStatus,
        Double confidence,
        int distance) {
}
