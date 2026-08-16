package dev.gamersden.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * The one non-2xx body shape (api-contract.md §1):
 * {@code { "error": { "code", "message", "details", "traceId" } }}.
 */
@Schema(name = "ErrorResponse", description = "Envelope returned for every non-2xx response")
public record ErrorResponse(Body error) {

    public static ErrorResponse of(ErrorCode code, String message, Map<String, Object> details, String traceId) {
        return new ErrorResponse(new Body(
                code.name(),
                message,
                details == null || details.isEmpty() ? null : details,
                traceId));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "ErrorBody")
    public record Body(
            @Schema(example = "SESSION_HAS_BALANCE") String code,
            @Schema(example = "Human-readable") String message,
            Map<String, Object> details,
            String traceId) {
    }
}
