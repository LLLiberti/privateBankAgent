package com.privatebank.agent.application.downstream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.agent.application.runtime.AgentExecutionRequest;
import com.privatebank.agent.application.runtime.AgentExecutionResult;
import com.privatebank.agent.application.runtime.BusinessAgentExecutor;
import com.privatebank.agent.application.runtime.StructuredAgentDefinition;
import com.privatebank.agent.application.runtime.StructuredAgentRuntime;
import com.privatebank.agent.config.AgentScopeProperties;
import com.privatebank.agent.domain.downstream.CfsDesignInput;
import com.privatebank.agent.domain.downstream.CfsDesignResult;
import com.privatebank.business.enums.workflow.AgentType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CfsDesignAgentExecutor implements BusinessAgentExecutor<CfsDesignInput, CfsDesignResult> {

    private static final String SYSTEM_PROMPT = """
            你是私行 CFS 方案设计 Agent。
            基于 KYC、市场洞察和产品专家结果，按照 CFS“3+6”结构生成方案初稿。
            所有结论必须来自输入 Artifact，不得编造事实或证据。
            输出必须符合 CfsDesignResult 结构。
            """;

    private final StructuredAgentRuntime runtime;
    private final ObjectMapper objectMapper;
    private final AgentScopeProperties properties;

    @Override
    public AgentType agentType() {
        return AgentType.SOLUTION_DESIGN;
    }

    @Override
    public AgentExecutionResult<CfsDesignResult> execute(AgentExecutionRequest<CfsDesignInput> request) {
        StructuredAgentDefinition<CfsDesignResult> definition = new StructuredAgentDefinition<>(
                "cfs-design-agent",
                SYSTEM_PROMPT,
                "请基于以下三个上游 Artifact 内容生成 CFS 方案。\n" + write(request.input()),
                CfsDesignResult.class,
                Math.max(1, properties.maxIterations()));
        return runtime.execute(request, definition);
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("CFS 方案输入无法序列化", exception);
        }
    }
}
