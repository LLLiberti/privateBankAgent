package com.privatebank.agent.application.kycchat;

import reactor.core.publisher.Flux;

public interface KycChatStreamingAgent {

    Flux<String> stream(KycChatAgentCommand command);
}
