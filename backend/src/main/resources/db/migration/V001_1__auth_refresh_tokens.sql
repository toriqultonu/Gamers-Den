-- B03 — auth: server-side state for the rotating refresh cookie (api-contract.md §1 "Auth").
--
-- Numbering: ARCHITECTURE.md §4.2 reserves V002 for tournaments and V003 for bookings, so this
-- ships as the 1.1 point-release of the baseline — strictly between V001 and V002, which keeps
-- every migration that lands after it in ascending order (no Flyway out-of-order needed).
--
-- The raw cookie value is opaque and NEVER stored: only its SHA-256 hex digest is, so a database
-- dump cannot be replayed as a session (ARCHITECTURE.md §5.12).

CREATE TABLE refresh_tokens (
  id BIGSERIAL PRIMARY KEY,
  staff_id BIGINT NOT NULL REFERENCES staff,
  terminal TEXT NOT NULL,
  token_hash TEXT NOT NULL UNIQUE,             -- SHA-256 hex of the opaque cookie value
  issued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at TIMESTAMPTZ NOT NULL,
  revoked_at TIMESTAMPTZ,                      -- set on rotation, logout, or reuse detection
  rotated_to BIGINT REFERENCES refresh_tokens  -- the successor issued when this one was spent
);

-- Reuse detection revokes the whole live family for a staff+terminal pair.
CREATE INDEX ON refresh_tokens (staff_id, terminal) WHERE revoked_at IS NULL;
CREATE INDEX ON refresh_tokens (expires_at);
