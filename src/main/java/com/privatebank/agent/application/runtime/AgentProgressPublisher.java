package com.privatebank.agent.application.runtime;

@FunctionalInterface
public interface AgentProgressPublisher {

    void publish(AgentProgressEvent event);
}
