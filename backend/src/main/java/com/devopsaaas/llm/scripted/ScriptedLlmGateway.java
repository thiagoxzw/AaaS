package com.devopsaaas.llm.scripted;

import com.devopsaaas.llm.LlmException;
import com.devopsaaas.llm.LlmFinishReason;
import com.devopsaaas.llm.LlmGateway;
import com.devopsaaas.llm.LlmMessage;
import com.devopsaaas.llm.LlmRequest;
import com.devopsaaas.llm.LlmResponse;
import com.devopsaaas.llm.LlmToolCall;
import com.devopsaaas.llm.LlmUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A deterministic "model" (RNF-MAN-03): the script is chosen by the last user message, and the turn by how
 * many assistant turns follow it in the request. It keeps no state, so a request rebuilt from the database
 * gets the same answer. It reports no tokens and no cost.
 */
public class ScriptedLlmGateway implements LlmGateway {

    static final String MODEL = "scripted-v1";
    private static final String LAST_TOOL_RESULT = "{{lastToolResult}}";
    private static final LlmScript FALLBACK = new LlmScript("fallback", Pattern.compile(""), Integer.MIN_VALUE,
            false, List.of(new LlmScript.Turn(
                    "There is no script for this message (LLM_PROVIDER=scripted). Try: \"is demo-api up?\"",
                    List.of(), LlmFinishReason.STOP, null)));

    private final ScriptRepository scripts;

    public ScriptedLlmGateway(ScriptRepository scripts) {
        this.scripts = scripts;
    }

    @Override
    public String provider() {
        return "scripted";
    }

    @Override
    public String model() {
        return MODEL;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        List<LlmMessage> messages = request.messages();
        int lastUser = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof LlmMessage.User) {
                lastUser = i;
                break;
            }
        }
        if (lastUser < 0) {
            throw new LlmException(LlmException.Category.INVALID_RESPONSE, "No user message in the request");
        }
        String userMessage = ((LlmMessage.User) messages.get(lastUser)).text();
        List<LlmMessage> sinceUser = messages.subList(lastUser + 1, messages.size());
        int turnIndex = (int) sinceUser.stream().filter(LlmMessage.Assistant.class::isInstance).count();

        LlmScript script = scripts.find(userMessage).orElse(FALLBACK);
        LlmScript.Turn turn = turn(script, turnIndex);
        if (turn.error() != null) {
            throw new LlmException(turn.error(), "Scripted failure: " + turn.error());
        }
        List<LlmToolCall> calls = new ArrayList<>();
        for (int i = 0; i < turn.toolCalls().size(); i++) {
            LlmScript.ToolCall call = turn.toolCalls().get(i);
            calls.add(new LlmToolCall("call-" + (turnIndex + 1) + "-" + (i + 1), call.name(), call.argumentsJson()));
        }
        String text = turn.text() == null ? null : turn.text().replace(LAST_TOOL_RESULT, lastToolResult(sinceUser));
        LlmFinishReason finishReason = turn.finishReason() != null ? turn.finishReason()
                : calls.isEmpty() ? LlmFinishReason.STOP : LlmFinishReason.TOOL_CALLS;
        return new LlmResponse(text, calls, finishReason, LlmUsage.NONE);
    }

    private static LlmScript.Turn turn(LlmScript script, int index) {
        if (index < script.turns().size()) {
            return script.turns().get(index);
        }
        if (script.repeatLastTurn()) {
            return script.turns().getLast();
        }
        return new LlmScript.Turn("The script '" + script.name() + "' has no more turns.", List.of(),
                LlmFinishReason.STOP, null);
    }

    private static String lastToolResult(List<LlmMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof LlmMessage.ToolResult result) {
                return result.content();
            }
        }
        return "(no tool result)";
    }
}
