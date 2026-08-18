package com.privatebank.agent.application.downstream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatebank.agent.application.runtime.AgentExecutionRequest;
import com.privatebank.agent.application.runtime.AgentExecutionResult;
import com.privatebank.agent.application.runtime.StructuredAgentDefinition;
import com.privatebank.agent.application.runtime.StructuredAgentRuntime;
import com.privatebank.agent.config.AgentScopeProperties;
import com.privatebank.agent.domain.downstream.CfsDesignInput;
import com.privatebank.agent.domain.downstream.CfsDesignResult;
import com.privatebank.business.enums.workflow.AgentType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CfsDesignAgentExecutorTest {

    @Test
    void includesThreePlusSixJsonRequirementsInSystemPrompt() {
        AtomicReference<StructuredAgentDefinition<?>> captured = new AtomicReference<>();
        StructuredAgentRuntime runtime = new StructuredAgentRuntime() {
            @Override
            public <I, O> AgentExecutionResult<O> execute(
                    AgentExecutionRequest<I> request, StructuredAgentDefinition<O> definition) {
                captured.set(definition);
                return new AgentExecutionResult<>(definition.outputType().cast(validResult()), 1, "test-model");
            }
        };

        executor(runtime).execute(request());

        assertThat(captured.get().systemPrompt())
                .contains("threePlusSixRequirements")
                .contains("客户个人情况", "人、企、家、社四维需求", "接触路径")
                .contains("实控人及其他关键人物详情", "公司大事记及财务分析")
                .contains("公司主要产品及服务介绍", "行业知识及竞争对手情况")
                .contains("公司及个人舆情", "工作优势及营销话术")
                .contains("恰好包含 6 个非空字符串");
    }

    private CfsDesignAgentExecutor executor(StructuredAgentRuntime runtime) {
        AgentScopeProperties properties = new AgentScopeProperties(
                new AgentScopeProperties.DeepSeek(null, null, null, null), 0, 4, 1);
        return new CfsDesignAgentExecutor(runtime, new ObjectMapper().findAndRegisterModules(), properties);
    }

    private AgentExecutionRequest<CfsDesignInput> request() {
        return new AgentExecutionRequest<>(
                "WF-1", "EXE-1", AgentType.SOLUTION_DESIGN, "SYSTEM",
                new CfsDesignInput(
                        "WF-1", "ART-KYC", "ART-MARKET", "ART-KYP",
                        "kyc", "market", "kyp", "CFS-3P6-V1", "INITIAL", null, null),
                Map.of());
    }

    private CfsDesignResult validResult() {
        return new CfsDesignResult(
                "C-1",
                new CfsDesignResult.InputArtifactRefs("ART-KYC", "ART-MARKET", "ART-KYP"),
                1,
                "strategy",
                "guide",
                "risk assessment",
                new CfsDesignResult.CfsStructure(
                        "customer information", "service plan", "marketing strategy",
                        List.of(
                                "controller details", "company events and financial analysis",
                                "products and services", "industry and competitors",
                                "company and personal public opinion", "advantages and marketing language")),
                List.of(), List.of(), List.of("SRC-1"), List.of(), List.of("RULE-1"));
    }
}
