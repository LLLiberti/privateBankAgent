package com.privatebank.agent.application.kyc;

import com.privatebank.agent.domain.kyc.KycGraphRelationship;

import java.util.List;

public interface KycGraphDataLoader {

    List<KycGraphRelationship> loadRelationships(Long personId);
}
