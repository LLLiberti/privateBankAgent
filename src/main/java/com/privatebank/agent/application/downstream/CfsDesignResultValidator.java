package com.privatebank.agent.application.downstream;

import com.privatebank.agent.domain.downstream.CfsDesignResult;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Lightweight validation applied immediately after CFS generation and before
 * the result is accepted as a successful artifact.  It only guarantees that the
 * report carries the mandatory risk/analysis sections expected by the downstream
 * compliance check; it does not replace the compliance agent.
 */
@Component
public class CfsDesignResultValidator {

    public void validate(CfsDesignResult result) {
        if (result == null) {
            throw new IllegalArgumentException("CFS生成结果为空");
        }
        if (!StringUtils.hasText(result.comprehensiveRiskAssessment())) {
            throw new IllegalArgumentException("CFS缺少综合风险评估 comprehensiveRiskAssessment");
        }
        if (result.cfsStructure() == null) {
            throw new IllegalArgumentException("CFS缺少3+6结构 cfsStructure");
        }
        if (result.pendingVerificationItems() == null) {
            throw new IllegalArgumentException("CFS缺少待核实项 pendingVerificationItems");
        }
        if (result.estimatedDataItems() == null) {
            throw new IllegalArgumentException("CFS缺少估算项 estimatedDataItems");
        }
        if (result.inputArtifactRefs() == null) {
            throw new IllegalArgumentException("CFS缺少上游Artifact引用 inputArtifactRefs");
        }
    }
}
