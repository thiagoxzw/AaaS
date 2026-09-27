package com.devopsaaas.llm;

import com.devopsaaas.llm.scripted.ScriptedLlmGateway;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Test-only wrapper around the scripted provider: it records every request (what the "model" saw) and runs
 * hooks registered for a marker in the user message, before the script answers. Hooks make timing
 * deterministic: "revoke the permission while the model is thinking", "cancel during the call".
 */
public class InterceptingLlmGateway implements LlmGateway {

    private final ScriptedLlmGateway delegate;
    private final List<LlmRequest> requests = new CopyOnWriteArrayList<>();
    private final Map<String, Consumer<LlmRequest>> hooks = new ConcurrentHashMap<>();

    public InterceptingLlmGateway(ScriptedLlmGateway delegate) {
        this.delegate = delegate;
    }

    /** Runs {@code hook} once, on the first call whose last user message contains {@code marker}. */
    public void onceWhen(String marker, Consumer<LlmRequest> hook) {
        hooks.put(marker, hook);
    }

    /** Requests whose last user message contains {@code marker}, oldest first. */
    public List<LlmRequest> requestsFor(String marker) {
        return requests.stream().filter(request -> lastUserMessage(request).contains(marker)).toList();
    }

    @Override
    public String provider() {
        return delegate.provider();
    }

    @Override
    public String model() {
        return delegate.model();
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        requests.add(request);
        String message = lastUserMessage(request);
        hooks.keySet().stream().filter(message::contains).findFirst()
                .map(hooks::remove)
                .ifPresent(hook -> hook.accept(request));
        return delegate.complete(request);
    }

    private static String lastUserMessage(LlmRequest request) {
        for (int i = request.messages().size() - 1; i >= 0; i--) {
            if (request.messages().get(i) instanceof LlmMessage.User user) {
                return user.text();
            }
        }
        return "";
    }
}
