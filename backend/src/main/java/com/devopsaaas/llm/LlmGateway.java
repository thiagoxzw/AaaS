package com.devopsaaas.llm;

/**
 * Port to a language model (ADR-0010). Provider-neutral: the agent never sees a provider's format. A gateway
 * only translates; it never executes tools (ADR-0002). What the model returns is an untrusted proposal.
 */
public interface LlmGateway {

    /** Provider name for metrics and records, for example {@code scripted}. */
    String provider();

    /** The model recorded on every execution ("which agent answered?"). */
    String model();

    /**
     * One turn of the conversation.
     *
     * @throws LlmException on timeout, rate limiting, provider errors or an unusable response
     */
    LlmResponse complete(LlmRequest request);
}
