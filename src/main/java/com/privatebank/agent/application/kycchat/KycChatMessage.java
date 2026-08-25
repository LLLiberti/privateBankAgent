package com.privatebank.agent.application.kycchat;

/** One masked message retained only in the in-memory chat session. */
public record KycChatMessage(Role role, String content) {

    public enum Role {
        USER,
        ASSISTANT
    }
}
