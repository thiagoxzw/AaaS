package com.devopsaaas.integration.openai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * A stand-in for the OpenAI Responses API on the JDK's HTTP server. Every request is recorded (headers and
 * body), and each answer is computed from the request by a scripted function, so a test controls what the
 * "model" says turn by turn. The response shapes follow the documented format as read for slice 6.
 */
public final class OpenAiStub implements AutoCloseable {

    public record Recorded(String path, String authorization, String body) {
    }

    public record Answer(int status, String body, Map<String, String> headers, long delayMillis) {

        public static Answer ok(String body) {
            return new Answer(200, body, Map.of(), 0);
        }

        public static Answer status(int status) {
            return new Answer(status, "{\"error\":{\"message\":\"echo of the prompt that must not leak\"}}",
                    Map.of(), 0);
        }
    }

    private final HttpServer server;
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private volatile Function<String, Answer> answers = body -> Answer.status(500);

    public OpenAiStub() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        server.createContext("/", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
    }

    public void answer(Function<String, Answer> function) {
        this.answers = function;
    }

    public List<Recorded> requests() {
        return List.copyOf(requests);
    }

    public void reset() {
        requests.clear();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new Recorded(exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Authorization"), body));
        Answer answer = answers.apply(body);
        try {
            if (answer.delayMillis() > 0) {
                Thread.sleep(answer.delayMillis());
            }
            answer.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            byte[] bytes = answer.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(answer.status(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException clientWentAway) {
            // The adapter gave up (timeout).
        } finally {
            exchange.close();
        }
    }

    // ---- canned bodies, in the Responses API shape ----------------------------------------------------

    public static String functionCall(String callId, String name, String arguments, int input, int output) {
        return """
                {"id":"resp_1","object":"response","status":"completed",
                 "output":[
                   {"type":"reasoning","id":"rs_1","summary":[]},
                   {"type":"message","id":"msg_1","role":"assistant","content":[{"type":"output_text","text":"Checking."}]},
                   {"type":"function_call","id":"fc_1","call_id":"%s","name":"%s","arguments":%s,"status":"completed"}
                 ],
                 "usage":{"input_tokens":%d,"output_tokens":%d,"total_tokens":%d}}
                """.formatted(callId, name, quote(arguments), input, output, input + output);
    }

    public static String text(String text, int input, int output) {
        return """
                {"id":"resp_2","object":"response","status":"completed",
                 "output":[{"type":"message","id":"msg_2","role":"assistant",
                            "content":[{"type":"output_text","text":%s}]}],
                 "usage":{"input_tokens":%d,"output_tokens":%d,"total_tokens":%d}}
                """.formatted(quote(text), input, output, input + output);
    }

    public static String truncated(String text) {
        return """
                {"id":"resp_3","object":"response","status":"incomplete",
                 "incomplete_details":{"reason":"max_output_tokens"},
                 "output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":%s}]}],
                 "usage":{"input_tokens":10,"output_tokens":1024}}
                """.formatted(quote(text));
    }

    static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
