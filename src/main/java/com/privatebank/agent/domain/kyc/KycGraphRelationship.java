package com.privatebank.agent.domain.kyc;

/**
 * Process-local Neo4j relationship projection used by KYC. Node names are retained only
 * so the masking boundary can associate runtime aliases with their source names; they must
 * never be copied into the model-facing relationship graph.
 */
public record KycGraphRelationship(
        String startNodeId,
        String startNodeType,
        String startNodeName,
        boolean startIsCustomer,
        String relationType,
        String endNodeId,
        String endNodeType,
        String endNodeName,
        boolean endIsCustomer,
        Long sourceId,
        String verificationStatus,
        Double confidence,
        int distance) {

    public KycGraphRelationship(
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
        this(startNodeId, startNodeType, null, startIsCustomer, relationType,
                endNodeId, endNodeType, null, endIsCustomer, sourceId,
                verificationStatus, confidence, distance);
    }
}
