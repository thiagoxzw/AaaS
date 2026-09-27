package com.devopsaaas.demo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChaosControllerTest {

    @Value("${local.server.port}")
    int port;

    private RestClient http() {
        return RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { }).build();
    }

    @Test
    void healthGoesDownOnDemand_andRecovers() {
        assertThat(health().getStatusCode().value()).isEqualTo(200);

        http().post().uri("/chaos/unhealthy").retrieve().toBodilessEntity();
        ResponseEntity<String> down = health();
        assertThat(down.getStatusCode().value()).isEqualTo(503);
        assertThat(down.getBody()).contains("DOWN");

        http().post().uri("/chaos/recover").retrieve().toBodilessEntity();
        assertThat(health().getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void logLine_isAccepted_andCannotBreakIntoSeveralLines() {
        ResponseEntity<String> response = http().post().uri("/chaos/log?msg={msg}", "line one\nforged line")
                .retrieve().toEntity(String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    private ResponseEntity<String> health() {
        return http().get().uri("/actuator/health").retrieve().toEntity(String.class);
    }
}
