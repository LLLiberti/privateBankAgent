package com.privatebank.business.dto.workflow;

import java.util.List;
import java.util.Map;

public record WorkflowResultResponse(
        String finalArtifactId,
        Integer versionNo,
        List<Map<String, Object>> files) {
}
