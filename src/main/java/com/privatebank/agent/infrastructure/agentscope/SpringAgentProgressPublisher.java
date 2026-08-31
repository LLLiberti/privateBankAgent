package com.privatebank.agent.infrastructure.agentscope;

import com.privatebank.agent.application.runtime.AgentProgressEvent;
import com.privatebank.agent.application.runtime.AgentProgressPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** Publishes Agent runtime progress without knowing any workflow delivery mechanism. */
@Component
@RequiredArgsConstructor
public class SpringAgentProgressPublisher implements AgentProgressPublisher {

    private final ApplicationEventPublisher eventPublisher;

    @Override
    public void publish(AgentProgressEvent event) {
        eventPublisher.publishEvent(event);
    }
}
