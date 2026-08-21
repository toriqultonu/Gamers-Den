package dev.gamersden.session.web;

import dev.gamersden.session.domain.ClockAction;
import jakarta.validation.constraints.NotNull;

/** {@code POST /sessions/{id}/clock} — {@code {action: START|PAUSE|RESUME}} (api-contract.md). */
public record ClockRequest(@NotNull ClockAction action) {
}
