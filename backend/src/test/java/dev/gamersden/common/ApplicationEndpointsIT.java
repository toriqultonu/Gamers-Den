package dev.gamersden.common;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamersden.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boot gate for B01: the app starts against a real Postgres (Flyway + JPA {@code validate}),
 * actuator answers, and the OpenAPI document the frontend generates types from is served.
 */
class ApplicationEndpointsIT extends AbstractIntegrationTest {

    @Autowired
    TestRestTemplate rest;

    @Test
    void actuatorHealthIsUp() {
        ResponseEntity<JsonNode> response = rest.getForEntity("/actuator/health", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("status").asText()).isEqualTo("UP");
    }

    @Test
    void openApiDocumentIsServed() {
        ResponseEntity<JsonNode> response = rest.getForEntity("/v3/api-docs", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body.get("info").get("title").asText()).isEqualTo("Gamer's Den API");
        assertThat(body.get("servers").get(0).get("url").asText()).isEqualTo("/api/v1");
    }

    @Test
    void unknownPathReturnsTheErrorEnvelope() {
        ResponseEntity<JsonNode> response = rest.getForEntity("/api/v1/nope", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("error").get("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(response.getBody().get("error").get("traceId").asText()).isNotBlank();
    }
}
