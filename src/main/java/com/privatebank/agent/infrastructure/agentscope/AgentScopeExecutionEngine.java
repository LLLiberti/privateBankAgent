package com.privatebank.agent.infrastructure.agentscope;

import com.privatebank.agent.application.runtime.AgentExecutionRequest;
import com.privatebank.agent.application.runtime.AgentExecutionResult;
import com.privatebank.agent.application.runtime.AgentProgressPublisher;
import com.privatebank.agent.application.runtime.AgentRuntimeException;
import com.privatebank.agent.application.runtime.StructuredAgentDefinition;
import com.privatebank.agent.application.runtime.StructuredAgentRuntime;
import com.privatebank.agent.config.AgentScopeProperties;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.Model;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class AgentScopeExecutionEngine implements StructuredAgentRuntime {

    private static final Set<GenerateReason> SUCCESS_REASONS = Set.of(
            GenerateReason.STRUCTURED_OUTPUT, GenerateReason.MODEL_STOP);

    private final Model privateBankAgentModel;
    private final AgentScopeProperties properties;
    private final AgentRuntimeContextFactory contextFactory;
    private final AgentProgressPublisher progressPublisher;

    @Override
    public <I, O> AgentExecutionResult<O> execute(
            AgentExecutionRequest<I> request,
            StructuredAgentDefinition<O> definition) {
        var builder = ReActAgent.builder()
                .name(definition.name())
                .sysPrompt(definition.systemPrompt())
                .model(privateBankAgentModel)
                .maxRetries(Math.max(0, properties.maxModelRetries()))
                .maxIters(Math.max(1, definition.maxIterations()))
                .middleware(new AgentScopeProgressMiddleware(request, progressPublisher));
        if (definition.toolkit() != null) {
            builder.toolkit(definition.toolkit());
        }
        ReActAgent agent = builder.build();
        try {
            Msg result = agent.call(
                            List.of(new UserMessage(definition.userPrompt())),
                            definition.outputType(),
                            contextFactory.create(request))
                    .block(properties.modelCallTimeout());
            if (result == null || result.getGenerateReason() == null
                    || !SUCCESS_REASONS.contains(result.getGenerateReason())
                    || !result.hasStructuredData()) {
                throw new AgentRuntimeException("AgentScope 未返回可用的结构化结果");
            }
            return new AgentExecutionResult<>(
                    result.getStructuredData(definition.outputType()),
                    1,
                    privateBankAgentModel.getModelName());
        } catch (AgentRuntimeException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AgentRuntimeException("AgentScope 执行失败", exception);
        } finally {
            agent.close();
        }
    }
}
