package com.privatebank.agent.infrastructure.agentscope;

import com.privatebank.agent.application.runtime.AgentExecutionRequest;
import io.agentscope.core.agent.RuntimeContext;
import org.springframework.stereotype.Component;

@Component
public class AgentRuntimeContextFactory {

    public RuntimeContext create(AgentExecutionRequest<?> request) {
        RuntimeContext.Builder builder = RuntimeContext.builder()
                .userId(request.operatorUserId())
                .sessionId(sessionId(request))
                .put("workflowId", request.workflowId())
                .put("executionId", request.executionId())
                .put("agentType", request.agentType().name());
        request.attributes().forEach(builder::put);
        return builder.build();
    }

    private String sessionId(AgentExecutionRequest<?> request) {
        return request.agentType().name() + ":" + request.workflowId() + ":" + request.executionId();
    }
}
