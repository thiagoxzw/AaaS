package com.devopsaaas.integration.docker;

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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A stand-in for the docker-socket-proxy, on the JDK's own HTTP server: scripted responses per path, and a
 * record of every request, so a test can prove what the adapter asked for (and what it never asked for).
 */
final class DockerApiStub implements AutoCloseable {

    record Response(int status, String contentType, byte[] body, long delayMillis) {
    }

    private final HttpServer server;
    private final Map<String, Response> responses = new ConcurrentHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();

    DockerApiStub() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    URI url() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    /** Requests as {@code "GET /v1.44/containers/x/json?..."}, oldest first. */
    List<String> requests() {
        return List.copyOf(requests);
    }

    void json(String method, String path, int status, String body) {
        respond(method, path, new Response(status, "application/json", body.getBytes(StandardCharsets.UTF_8), 0));
    }

    void respond(String method, String path, Response response) {
        responses.put(method + " " + path, response);
    }

    private void handle(HttpExchange exchange) throws IOException {
        URI uri = exchange.getRequestURI();
        String method = exchange.getRequestMethod();
        requests.add(method + " " + uri);
        Response response = responses.getOrDefault(method + " " + uri.getPath(),
                new Response(404, "application/json",
                        "{\"message\":\"No such container\"}".getBytes(StandardCharsets.UTF_8), 0));
        try {
            if (response.delayMillis() > 0) {
                Thread.sleep(response.delayMillis());
            }
            if (response.contentType() != null) {
                exchange.getResponseHeaders().add("Content-Type", response.contentType());
            }
            exchange.sendResponseHeaders(response.status(), response.body().length == 0 ? -1 : response.body().length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response.body());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException clientWentAway) {
            // The adapter gave up (read timeout); nothing to answer.
        } finally {
            exchange.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
