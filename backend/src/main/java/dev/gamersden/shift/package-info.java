/**
 * Shifts, X/Z computation, expenses. Owns: shifts, expenses.
 *
 * <p>Layering (ARCHITECTURE.md §3): {@code web/} (controllers + DTOs) → {@code domain/}
 * (entities, services) → {@code repo/} (Spring Data). No cross-package repository access —
 * call the owning package's service.
 */
package dev.gamersden.shift;
