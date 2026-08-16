/**
 * ESC/POS render (P1-P7), print jobs, queue worker, device port, printers. Owns: print_jobs.
 *
 * <p>Layering (ARCHITECTURE.md §3): {@code web/} (controllers + DTOs) → {@code domain/}
 * (entities, services) → {@code repo/} (Spring Data). No cross-package repository access —
 * call the owning package's service.
 */
package dev.gamersden.printing;
