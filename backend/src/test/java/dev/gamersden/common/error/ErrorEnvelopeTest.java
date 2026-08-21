package dev.gamersden.common.error;

import dev.gamersden.common.config.WebMvcConfig;
import dev.gamersden.common.trace.TraceId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The error envelope is a published contract (api-contract.md §1) — these assertions are the
 * guard rail for every later feature.
 *
 * <p>The slice is pinned to the probe controller and runs with the filter chain off: this is a
 * test of the {@code @RestControllerAdvice}, not of the auth guards B03 put in front of it —
 * those have their own coverage in {@code auth/web}.
 */
@WebMvcTest(controllers = ErrorEnvelopeTest.ProbeController.class,
        excludeAutoConfiguration = {SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class})
@ActiveProfiles("test")
@Import({WebMvcConfig.class, ErrorEnvelopeTest.ProbeController.class})
class ErrorEnvelopeTest {

    private static final String BASE = WebMvcConfig.API_BASE_PATH + "/test-probe";

    @Autowired
    MockMvc mockMvc;

    @Test
    void thrownApiExceptionRendersTheEnvelopeWithItsCodeStatusAndDetails() throws Exception {
        mockMvc.perform(get(BASE + "/conflict"))
                .andExpect(status().isConflict())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(jsonPath("$.error.code").value("SESSION_HAS_BALANCE"))
                .andExpect(jsonPath("$.error.message").value("Session 42 still owes 250"))
                .andExpect(jsonPath("$.error.details.sessionId").value(42))
                .andExpect(jsonPath("$.error.traceId").isNotEmpty());
    }

    @Test
    void traceIdInTheEnvelopeMatchesTheInboundHeader() throws Exception {
        mockMvc.perform(get(BASE + "/conflict").header(TraceId.HEADER, "trace-abc"))
                .andExpect(status().isConflict())
                .andExpect(header().string(TraceId.HEADER, "trace-abc"))
                .andExpect(jsonPath("$.error.traceId").value("trace-abc"));
    }

    @Test
    void notFoundRendersNotFoundCode() throws Exception {
        mockMvc.perform(get(BASE + "/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.error.details.entity").value("Station"))
                .andExpect(jsonPath("$.error.details.id").value("9"));
    }

    @Test
    void beanValidationFailureRendersValidationFailedWithFieldDetails() throws Exception {
        mockMvc.perform(post(BASE + "/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"blocks\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.fields.name").isNotEmpty())
                .andExpect(jsonPath("$.error.details.fields.blocks").isNotEmpty());
    }

    @Test
    void unmappedUrlStillRendersTheEnvelope() throws Exception {
        mockMvc.perform(get(WebMvcConfig.API_BASE_PATH + "/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void unexpectedFailureRendersInternalErrorWithoutLeakingTheCause() throws Exception {
        mockMvc.perform(get(BASE + "/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.error.message").value("Unexpected server error"));
    }

    @Test
    void detailsAreOmittedWhenEmpty() throws Exception {
        mockMvc.perform(get(BASE + "/forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.error.details").doesNotExist());
    }

    @RestController
    @RequestMapping("/test-probe")
    static class ProbeController {

        @GetMapping("/conflict")
        void conflict() {
            throw new ConflictException(ErrorCode.SESSION_HAS_BALANCE, "Session 42 still owes 250",
                    Map.of("sessionId", 42));
        }

        @GetMapping("/missing")
        void missing() {
            throw new NotFoundException("Station", 9);
        }

        @GetMapping("/forbidden")
        void forbidden() {
            throw new ForbiddenException("Manager+ only");
        }

        @GetMapping("/boom")
        void boom() {
            throw new IllegalStateException("secret internals: db password rotation failed");
        }

        @PostMapping("/echo")
        void echo(@Valid @RequestBody ProbeRequest request) {
        }
    }

    record ProbeRequest(@NotBlank String name, @Min(1) int blocks) {
    }
}
