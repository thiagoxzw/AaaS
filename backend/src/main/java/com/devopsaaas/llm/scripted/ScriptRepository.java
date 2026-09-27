package com.devopsaaas.llm.scripted;

import java.util.Optional;

/** Where the scripted provider finds its scripts: loading and choosing a script is not the gateway's job. */
public interface ScriptRepository {

    /** The script that answers this user message, if any. */
    Optional<LlmScript> find(String userMessage);
}
