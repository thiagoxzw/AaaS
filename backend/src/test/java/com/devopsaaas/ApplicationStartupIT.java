package com.devopsaaas;

import static org.assertj.core.api.Assertions.assertThat;

import com.devopsaaas.shared.observability.RequestIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Slice 0: the application boots against a real PostgreSQL, Flyway applies the migrations and the
 * Actuator is served only on the management port.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@Testcontainers
class ApplicationStartupIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine");

    @Value("${local.server.port}")
    int apiPort;

    @Value("${local.management.port}")
    int managementPort;

    @Autowired
    JdbcTemplate jdbc;

    private final RestClient http = RestClient.builder()
            .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { })
            .build();

    @Test
    void healthIsUp_onManagementPort() {
        ResponseEntity<String> response = get(managementPort, "/actuator/health");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void actuatorIsNotExposed_onApiPort() {
        assertThat(get(apiPort, "/actuator/health").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(apiPort, "/actuator/prometheus").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void prometheusMetricsAreExposed_onManagementPort() {
        ResponseEntity<String> response = get(managementPort, "/actuator/prometheus");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("jvm_memory_used_bytes");
    }

    @Test
    void flywayCreatesOrganizationTable() {
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class);
        String table = jdbc.queryForObject("SELECT to_regclass('public.organization')::text", String.class);

        assertThat(applied).isGreaterThanOrEqualTo(1);
        assertThat(table).isEqualTo("organization");
    }

    @Test
    void everyApiResponseCarriesRequestId() {
        ResponseEntity<String> response = get(apiPort, "/does-not-exist");

        assertThat(response.getHeaders().getFirst(RequestIdFilter.HEADER)).isNotBlank();
    }

    private ResponseEntity<String> get(int port, String path) {
        return http.get().uri("http://localhost:" + port + path).retrieve().toEntity(String.class);
    }
}
