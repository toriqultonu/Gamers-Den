/**
 * Interfaces one feature package publishes for another to implement, so a caller never touches a
 * foreign repository (ARCHITECTURE.md §3). The interface lives here; the implementation lives in
 * the package that owns the tables.
 */
package dev.gamersden.common.spi;
