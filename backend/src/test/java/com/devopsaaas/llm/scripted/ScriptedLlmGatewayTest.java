package com.devopsaaas.llm.scripted;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devopsaaas.llm.LlmException;
import com.devopsaaas.llm.LlmFinishReason;
import com.devopsaaas.llm.LlmMessage;
import com.devopsaaas.llm.LlmRequest;
import com.devopsaaas.llm.LlmResponse;
import com.devopsaaas.llm.LlmToolCall;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ScriptedLlmGatewayTest {

    private static final LlmScript STATUS = new LlmScript("status", Pattern.compile("status"), 0, false, List.of(
            new LlmScript.Turn("checking", List.of(new LlmScript.ToolCall("getContainerStatus",
                    "{\"service\":\"demo-api\"}")), null, null),
            new LlmScript.Turn("result: {{lastToolResult}}", List.of(), null, null)));
    private static final LlmScript LOOP = new LlmScript("loop", Pattern.compile("loop"), 0, true, List.of(
            new LlmScript.Turn("again", List.of(new LlmScript.ToolCall("x", "{}")), null, null)));
    private static final LlmScript FAIL = new LlmScript("fail", Pattern.compile("fail"), 0, false, List.of(
            new LlmScript.Turn(null, List.of(), null, LlmException.Category.TIMEOUT)));

    private final ScriptedLlmGateway gateway = new ScriptedLlmGateway(message -> Optional.ofNullable(
            message.contains("status") ? STATUS : message.contains("loop") ? LOOP
                    : message.contains("fail") ? FAIL : null));

    @Test
    void theTurn_isTheNumberOfAssistantTurnsAfterTheLastUserMessage() {
        LlmResponse first = gateway.complete(request(new LlmMessage.User("status?")));
        LlmResponse second = gateway.complete(request(
                new LlmMessage.User("status?"),
                new LlmMessage.Assistant("checking", List.of(new LlmToolCall("call-1-1", "getContainerStatus", "{}"))),
                new LlmMessage.ToolResult("call-1-1", "getContainerStatus", "{\"state\":\"RUNNING\"}")));

        assertThat(first.finishReason()).isEqualTo(LlmFinishReason.TOOL_CALLS);
        assertThat(first.toolCalls()).containsExactly(
                new LlmToolCall("call-1-1", "getContainerStatus", "{\"service\":\"demo-api\"}"));
        assertThat(second.finishReason()).isEqualTo(LlmFinishReason.STOP);
        assertThat(second.text()).isEqualTo("result: {\"state\":\"RUNNING\"}");
    }

    @Test
    void earlierExchanges_doNotShiftTheTurn() {
        LlmResponse response = gateway.complete(request(
                new LlmMessage.User("status? (earlier)"),
                new LlmMessage.Assistant("old answer", List.of()),
                new LlmMessage.User("status?")));

        assertThat(response.text()).isEqualTo("checking");
    }

    @Test
    void aLoopingScript_repeatsItsLastTurn_andOthersEndPolitely() {
        LlmMessage.Assistant turn = new LlmMessage.Assistant("again", List.of());

        assertThat(gateway.complete(request(new LlmMessage.User("loop"), turn, turn, turn)).text()).isEqualTo("again");
        assertThat(gateway.complete(request(new LlmMessage.User("status"), turn, turn, turn)).text())
                .contains("no more turns");
    }

    @Test
    void scriptedFailures_andUnknownMessages() {
        assertThatThrownBy(() -> gateway.complete(request(new LlmMessage.User("fail"))))
                .isInstanceOfSatisfying(LlmException.class,
                        exception -> assertThat(exception.category()).isEqualTo(LlmException.Category.TIMEOUT));
        assertThat(gateway.complete(request(new LlmMessage.User("hello"))).text()).contains("no script");
    }

    private static LlmRequest request(LlmMessage... messages) {
        return new LlmRequest("system", List.of(messages), List.of(), 100, Duration.ofSeconds(1));
    }
}
