package com.devopsaaas.agent;

/** Only the user and the agent's final answers: tool calls and intermediate turns have their own tables. */
public enum MessageRole {
    USER, ASSISTANT
}
