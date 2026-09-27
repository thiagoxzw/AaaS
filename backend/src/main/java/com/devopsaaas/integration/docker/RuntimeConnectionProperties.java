package com.devopsaaas.integration.docker;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Runtime connections by logical name (RF-13): an environment stores only the name, and the URL lives here,
 * in configuration. The URL points to the docker-socket-proxy, never to the Docker socket itself (ADR-011).
 *
 * @param connections for example {@code local -> http://docker-socket-proxy:2375}
 * @param docker adapter settings shared by every connection
 */
@ConfigurationProperties("devops.runtime")
public record RuntimeConnectionProperties(Map<String, Connection> connections, @DefaultValue Docker docker) {

    public RuntimeConnectionProperties {
        connections = connections == null ? Map.of() : Map.copyOf(connections);
        connections.forEach((name, connection) -> {
            if (connection == null || connection.dockerUrl() == null) {
                throw new IllegalStateException("devops.runtime.connections." + name + ".docker-url must be set");
            }
            URI url = connection.dockerUrl();
            if (!"http".equals(url.getScheme()) && !"https".equals(url.getScheme())) {
                throw new IllegalStateException("devops.runtime.connections." + name
                        + ".docker-url must be an http(s) URL to the docker-socket-proxy");
            }
            if (url.getUserInfo() != null) {
                throw new IllegalStateException("devops.runtime.connections." + name
                        + ".docker-url must not embed credentials");
            }
        });
    }

    public record Connection(URI dockerUrl) {
    }

    /**
     * @param apiVersion Docker Engine API version every request is pinned to
     * @param connectTimeout and {@code readTimeout} stay below the tools' timeouts, so a call that hangs ends
     *     in the client instead of leaving a thread running after the executor gave up
     * @param maxResponseBytes cap on any response read into memory, whatever the log tail asks for
     * @param maxLineLength longer log lines are cut
     */
    public record Docker(
            @DefaultValue("1.44") String apiVersion,
            @DefaultValue("2s") Duration connectTimeout,
            @DefaultValue("8s") Duration readTimeout,
            @DefaultValue("1048576") int maxResponseBytes,
            @DefaultValue("2000") int maxLineLength) {

        public Docker {
            if (apiVersion == null || !apiVersion.matches("^1\\.\\d{2}$")) {
                throw new IllegalStateException("devops.runtime.docker.api-version must look like 1.44");
            }
        }
    }
}
