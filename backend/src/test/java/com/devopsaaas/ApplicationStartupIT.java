package com.devopsaaas;

import static org.assertj.core.api.Assertions.assertThat;

import com.devopsaaas.shared.observability.RequestIdFilter;
import com.devopsaaas.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/**
 * Slice 0: the application boots against a real PostgreSQL, Flyway applies the migrations and the
 * Actuator is served only on the management port.
 */
class ApplicationStartupIT extends IntegrationTest {

    @Value("${local.management.port}")
    int managementPort;

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
        // Deny by default: an anonymous caller cannot even probe whether the path exists (401) ...
        assertThat(get(apiPort, "/actuator/health").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get(apiPort, "/actuator/prometheus").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // ... and even an authenticated admin finds nothing there (404).
        String token = createAdmin(DEFAULT_ORGANIZATION).token();
        assertThat(get("/actuator/prometheus", token).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
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

        assertThat(applied).isGreaterThanOrEqualTo(5);
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
