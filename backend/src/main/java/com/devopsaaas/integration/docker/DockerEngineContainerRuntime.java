package com.devopsaaas.integration.docker;

import com.devopsaaas.tool.container.ContainerLogs;
import com.devopsaaas.tool.container.ContainerRef;
import com.devopsaaas.tool.container.ContainerRuntime;
import com.devopsaaas.tool.container.ContainerRuntimeException;
import com.devopsaaas.tool.container.ContainerRuntimeException.Category;
import com.devopsaaas.tool.container.ContainerSnapshot;
import com.devopsaaas.tool.container.ContainerState;
import com.devopsaaas.tool.container.HealthStatus;
import com.devopsaaas.tool.container.LogQuery;
import com.devopsaaas.tool.container.RuntimeVersion;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link ContainerRuntime} over the Docker Engine API, reached only through the docker-socket-proxy
 * (ADR-011). What it guarantees:
 *
 * <ul>
 *   <li>every request is pinned to one API version and addressed by the container name that
 *       {@link ContainerRef} carries, which comes from the allowlist;</li>
 *   <li>only domain fields leave the adapter: {@code Config.Env} is never even turned into an object;</li>
 *   <li>connect and read timeouts below the tools' timeouts, no HTTP proxy, no redirects;</li>
 *   <li>bounded reads: every response is capped in bytes, and long log lines are cut;</li>
 *   <li>failures leave as {@link ContainerRuntimeException} categories, never as Docker messages.</li>
 * </ul>
 */
@Component
@EnableConfigurationProperties(RuntimeConnectionProperties.class)
public class DockerEngineContainerRuntime implements ContainerRuntime {

    private static final Logger log = LoggerFactory.getLogger(DockerEngineContainerRuntime.class);
    private static final String NO_DATE = "0001-01-01T00:00:00Z";

    private final RuntimeConnectionProperties.Docker settings;
    private final Map<String, RestClient> clients = new LinkedHashMap<>();
    private final List<HttpClient> httpClients;
    private final String apiPrefix;
    private final MeterRegistry meters;
    private final JsonMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    DockerEngineContainerRuntime(RuntimeConnectionProperties properties, MeterRegistry meters) {
        this.settings = properties.docker();
        this.apiPrefix = "/v" + settings.apiVersion();
        this.meters = meters;
        List<HttpClient> created = new ArrayList<>();
        properties.connections().forEach((name, connection) -> {
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(settings.connectTimeout())
                    // The proxy sits on an internal network: never route through an HTTP proxy, never follow
                    // a redirect somewhere else.
                    .proxy(HttpClient.Builder.NO_PROXY)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();
            created.add(httpClient);
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
            factory.setReadTimeout(settings.readTimeout());
            clients.put(name, RestClient.builder()
                    .baseUrl(connection.dockerUrl().toString())
                    .requestFactory(factory)
                    .build());
        });
        this.httpClients = List.copyOf(created);
    }

    // ---- port -------------------------------------------------------------------------------------------

    @Override
    public List<ContainerSnapshot> list(Collection<ContainerRef> refs) {
        // One inspect per allowlisted container, by exact name: nothing outside the allowlist is ever asked
        // for, and there is no name-substring filter to get wrong.
        return refs.stream().map(ref -> {
            try {
                return inspect(ref);
            } catch (ContainerRuntimeException exception) {
                if (exception.category() == Category.NOT_FOUND) {
                    return ContainerSnapshot.notFound(ref.serviceName());
                }
                throw exception;
            }
        }).toList();
    }

    @Override
    public ContainerSnapshot inspect(ContainerRef ref) {
        DockerInspect inspect = call("inspect", ref.connectionRef(), client -> client.get()
                .uri(apiPrefix + "/containers/{name}/json", ref.containerName())
                .exchange((request, response) -> {
                    failOnError(response.getStatusCode());
                    return mapper.readValue(read(response.getBody()).bytes(), DockerInspect.class);
                }));
        return toSnapshot(ref, inspect);
    }

    @Override
    public ContainerLogs logs(ContainerRef ref, LogQuery query) {
        return call("logs", ref.connectionRef(), client -> client.get()
                .uri(uri -> {
                    uri.path(apiPrefix + "/containers/{name}/logs")
                            .queryParam("stdout", 1)
                            .queryParam("stderr", 1)
                            .queryParam("timestamps", 1)
                            .queryParam("tail", query.tail());
                    if (query.since() != null) {
                        uri.queryParam("since", unixTime(query.since()));
                    }
                    return uri.build(ref.containerName());
                })
                .exchange((request, response) -> {
                    failOnError(response.getStatusCode());
                    Body body = read(response.getBody());
                    String contentType = response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
                    DockerLogDecoder.Decoded decoded = DockerLogDecoder.decode(body.bytes(),
                            DockerLogDecoder.isMultiplexed(contentType, body.bytes()), body.cut(),
                            settings.maxLineLength());
                    return new ContainerLogs(ref.serviceName(), decoded.lines(), decoded.truncated());
                }));
    }

    @Override
    public void restart(ContainerRef ref, Duration gracefulStopTimeout) {
        call("restart", ref.connectionRef(), client -> client.post()
                .uri(apiPrefix + "/containers/{name}/restart?t={seconds}", ref.containerName(),
                        gracefulStopTimeout.toSeconds())
                .exchange((request, response) -> {
                    failOnError(response.getStatusCode());
                    return Boolean.TRUE;
                }));
    }

    @Override
    public RuntimeVersion version(String connectionRef) {
        DockerVersion version = call("version", connectionRef, client -> client.get()
                .uri(apiPrefix + "/version")
                .exchange((request, response) -> {
                    failOnError(response.getStatusCode());
                    return mapper.readValue(read(response.getBody()).bytes(), DockerVersion.class);
                }));
        return new RuntimeVersion(version.version(), version.apiVersion());
    }

    // ---- HTTP -------------------------------------------------------------------------------------------

    private record Body(byte[] bytes, boolean cut) {
    }

    private Body read(InputStream stream) throws IOException {
        byte[] bytes = stream.readNBytes(settings.maxResponseBytes() + 1);
        return bytes.length > settings.maxResponseBytes()
                ? new Body(Arrays.copyOf(bytes, settings.maxResponseBytes()), true)
                : new Body(bytes, false);
    }

    /** Docker's error messages are not propagated: they may echo names, and callers only need the category. */
    private static void failOnError(HttpStatusCode status) {
        if (status.is2xxSuccessful()) {
            return;
        }
        Category category = switch (status.value()) {
            case 404 -> Category.NOT_FOUND;
            // The proxy denies what its configuration does not allow.
            case 403 -> Category.FORBIDDEN;
            default -> status.is5xxServerError() ? Category.UNAVAILABLE : Category.UNEXPECTED;
        };
        throw new ContainerRuntimeException(category, "Docker API answered HTTP " + status.value());
    }

    @SuppressFBWarnings(value = "CRLF_INJECTION_LOGS", justification = "operation is one of this class's own "
            + "literals (inspect, logs, restart, version); logs are JSON-encoded as well")
    private <T> T call(String operation, String connectionRef, Function<RestClient, T> request) {
        RestClient client = clients.get(connectionRef);
        Timer.Sample sample = Timer.start(meters);
        String outcome = "success";
        try {
            if (client == null) {
                throw new ContainerRuntimeException(Category.UNAVAILABLE,
                        "No runtime connection is configured under that name");
            }
            return request.apply(client);
        } catch (ContainerRuntimeException exception) {
            outcome = exception.category().name();
            throw exception;
        } catch (ResourceAccessException exception) {
            // Connection refused, DNS failure, connect or read timeout: possibly transient.
            outcome = Category.UNAVAILABLE.name();
            throw new ContainerRuntimeException(Category.UNAVAILABLE, "The container runtime could not be reached",
                    exception);
        } catch (RestClientException | JacksonException exception) {
            outcome = Category.UNEXPECTED.name();
            log.warn("Unexpected Docker API response for operation {}", operation, exception);
            throw new ContainerRuntimeException(Category.UNEXPECTED, "Unexpected Docker API response", exception);
        } finally {
            sample.stop(meters.timer("devops.runtime.calls", "operation", operation, "outcome", outcome));
        }
    }

    // ---- mapping ----------------------------------------------------------------------------------------

    private static ContainerSnapshot toSnapshot(ContainerRef ref, DockerInspect inspect) {
        DockerInspect.State state = inspect.state();
        if (state == null) {
            throw new ContainerRuntimeException(Category.UNEXPECTED, "Inspect response without State");
        }
        return new ContainerSnapshot(
                // The LLM-facing name is the logical one, never the real container name.
                ref.serviceName(),
                containerState(state.status()),
                health(state.health()),
                state.exitCode(),
                state.oomKilled(),
                inspect.restartCount(),
                instant(state.startedAt()),
                instant(state.finishedAt()),
                inspect.config() == null ? null : inspect.config().image());
    }

    static ContainerState containerState(String status) {
        if (status == null) {
            return ContainerState.UNKNOWN;
        }
        // Docker's values are lowercase ASCII; anything else is UNKNOWN rather than guessed.
        return switch (status) {
            case "created" -> ContainerState.CREATED;
            case "running" -> ContainerState.RUNNING;
            case "paused" -> ContainerState.PAUSED;
            case "restarting" -> ContainerState.RESTARTING;
            case "removing" -> ContainerState.REMOVING;
            case "exited" -> ContainerState.EXITED;
            case "dead" -> ContainerState.DEAD;
            default -> ContainerState.UNKNOWN;
        };
    }

    static HealthStatus health(DockerInspect.Health health) {
        if (health == null || health.status() == null) {
            return HealthStatus.NONE;
        }
        return switch (health.status()) {
            case "starting" -> HealthStatus.STARTING;
            case "healthy" -> HealthStatus.HEALTHY;
            case "unhealthy" -> HealthStatus.UNHEALTHY;
            default -> HealthStatus.NONE;
        };
    }

    /** Docker reports "never" as year 1. */
    /**
     * Docker's {@code since}: seconds since the epoch, with the fraction. Whole seconds would let a restart within
     * the same second leak the previous run's lines (verified on Docker 29.3.1).
     */
    static String unixTime(Instant instant) {
        return instant.getEpochSecond() + "." + String.format(Locale.ROOT, "%09d", instant.getNano());
    }

    static Instant instant(String value) {
        if (value == null || value.isBlank() || NO_DATE.equals(value)) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    @PreDestroy
    void close() {
        httpClients.forEach(HttpClient::close);
    }
}
