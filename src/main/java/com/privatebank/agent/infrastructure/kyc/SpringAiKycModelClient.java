package com.privatebank.agent.infrastructure.kyc;

import com.privatebank.agent.application.kyc.KycModelClient;
import com.privatebank.agent.domain.kyc.KycModelInvocationException;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class SpringAiKycModelClient implements KycModelClient {

    private final ChatModel chatModel;

    @Value("${spring.ai.deepseek.chat.options.model:deepseek-v4-flash}")
    private String modelName;

    @Override
    public String generate(String systemPrompt, String userPrompt) {
        ChatResponse response = chatModel.call(new Prompt(
                new SystemMessage(systemPrompt),
                new UserMessage(userPrompt)));
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null
                || !StringUtils.hasText(response.getResult().getOutput().getText())) {
            throw new KycModelInvocationException("KYC 模型未返回可用内容");
        }
        return response.getResult().getOutput().getText();
    }

    @Override
    public String modelName() {
        return modelName;
    }
}
