package com.privatebank.agent.config;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.formatter.DeepSeekFormatter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AgentScopeConfiguration {

    @Bean
    public Model privateBankAgentModel(AgentScopeProperties properties) {
        AgentScopeProperties.DeepSeek deepSeek = properties.deepseek();
        return OpenAIChatModel.builder()
                .apiKey(deepSeek.apiKey())
                .baseUrl(deepSeek.baseUrl())
                .modelName(deepSeek.model())
                .stream(true)
                .generateOptions(GenerateOptions.builder()
                        .temperature(deepSeek.temperature())
                        .build())
                .formatter(new DeepSeekFormatter())
                .nativeStructuredOutput(false)
                .nativeStructuredOutputWithTools(false)
                .build();
    }
}
