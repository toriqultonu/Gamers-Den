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
        // The /api/v1 prefix lives in the paths (springdoc resolves WebMvcConfig's path prefix),
        // so the server URL must NOT repeat it — a server of /api/v1 makes every Swagger UI
        // "Try it out" call /api/v1/api/v1/... and 401 on the unmatched path.
        assertThat(body.get("paths").has("/api/v1/auth/login")).isTrue();
        assertThat(body.get("servers").get(0).get("url").asText()).doesNotContain("/api/v1");
    }

    /**
     * B03 put the API behind the JWT filter chain, so an anonymous caller is turned away before
     * routing ever runs — an unknown path is indistinguishable from a real one, by design.
     */
    @Test
    void anUnauthenticatedApiCallReturnsThe401Envelope() {
        ResponseEntity<JsonNode> response = rest.getForEntity("/api/v1/nope", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("error").get("code").asText()).isEqualTo("UNAUTHORIZED");
        assertThat(response.getBody().get("error").get("traceId").asText()).isNotBlank();
    }
}
