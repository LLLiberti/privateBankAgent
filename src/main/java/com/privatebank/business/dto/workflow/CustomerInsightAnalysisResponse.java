package com.privatebank.business.dto.workflow;

import com.privatebank.business.enums.workflow.AgentStatus;
import com.privatebank.business.enums.workflow.WorkflowStatus;

import java.time.LocalDateTime;
import java.util.List;

/** Latest persisted customer-insight analysis for a workflow. */
public record CustomerInsightAnalysisResponse(
        String workflowId,
        WorkflowStatus workflowStatus,
        AgentStatus agentStatus,
        String artifactId,
        String executionId,
        Integer version,
        LocalDateTime createdAt,
        boolean actionable,
        Analysis analysis) {

    public record Analysis(
            String riskLevel,
            String summary,
            List<Finding> findings,
            List<String> riskAlerts,
            List<String> recommendedActions,
            List<String> dataGaps,
            GraphAssessment graphAssessment,
            List<FollowUpQuestion> followUpQuestions) {

        public Analysis(
                String riskLevel,
                String summary,
                List<Finding> findings,
                List<String> riskAlerts,
                List<String> recommendedActions,
                List<String> dataGaps,
                GraphAssessment graphAssessment) {
            this(riskLevel, summary, findings, riskAlerts, recommendedActions, dataGaps,
                    graphAssessment, List.of());
        }
    }

    public record Finding(
            String dimension,
            String riskLevel,
            String finding,
            List<String> evidenceRefs) {
    }

    public record GraphAssessment(
            String contribution,
            String summary,
            List<String> evidenceRefs) {
    }

    public record FollowUpQuestion(
            String id,
            String question) {
    }
}
