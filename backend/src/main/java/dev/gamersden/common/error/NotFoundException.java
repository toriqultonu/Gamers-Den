package dev.gamersden.common.error;

import java.util.Map;

/** 404 {@code NOT_FOUND}. */
public class NotFoundException extends ApiException {

    public NotFoundException(String message) {
        super(ErrorCode.NOT_FOUND, message);
    }

    public NotFoundException(String entity, Object id) {
        super(ErrorCode.NOT_FOUND, entity + " " + id + " not found",
                Map.of("entity", entity, "id", String.valueOf(id)));
    }
}
