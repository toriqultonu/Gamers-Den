-- V003__bookings.sql — pre-booking settings, bookings and the daily queue tokens (TASK B15).
--
-- DDL below is a VERBATIM copy of docs/bookings.md §5 (TASKLIST GLOBAL RULE 4: copy, never
-- redesign), in the order that file writes it. backend/ARCHITECTURE.md §4.2 assigns this file
-- number: V001 core, V002 tournaments, V003 bookings/queue.
--
-- queue_entries ships here with the rest of the schema even though the walk-up play queue and the
-- seat transaction are B16 — the migration is the document's schema, not this task's subset.
-- Booking check-in (B15) already writes source = 'BOOKING' rows. Additive-only.

CREATE TABLE booking_settings (                -- single row
  id BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (id),
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  package_fee INT NOT NULL DEFAULT 100 CHECK (package_fee >= 0),
  cancel_cutoff_hours INT NOT NULL DEFAULT 2 CHECK (cancel_cutoff_hours >= 0),
  updated_by BIGINT REFERENCES staff, updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE bookings (
  id BIGSERIAL PRIMARY KEY,
  station_id BIGINT NOT NULL REFERENCES stations,
  member_id BIGINT REFERENCES members,
  name TEXT NOT NULL, phone TEXT,
  start_at TIMESTAMPTZ NOT NULL,
  blocks INT NOT NULL CHECK (blocks >= 1),     -- 30-min units prepaid
  play_amount INT NOT NULL,                    -- snapshot at sale
  package_fee INT NOT NULL,                    -- snapshot at sale
  cutoff_hours INT NOT NULL,                   -- snapshot at sale
  tx_id BIGINT NOT NULL REFERENCES transactions,
  status TEXT NOT NULL DEFAULT 'PAID' CHECK (status IN ('PAID','ARRIVED','USED','CANCELLED')),
  refund_tx_id BIGINT REFERENCES transactions,
  queue_entry_id BIGINT,                       -- set at check-in (FK below)
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ON bookings (status, start_at);

CREATE TABLE queue_entries (                   -- one row per issued token
  id BIGSERIAL PRIMARY KEY,
  token_date DATE NOT NULL,
  token_no INT NOT NULL,                       -- daily sequence
  source TEXT NOT NULL CHECK (source IN ('BOOKING','PLAY_TICKET')),
  booking_id BIGINT REFERENCES bookings,
  tx_id BIGINT NOT NULL REFERENCES transactions,
  player_name TEXT NOT NULL,
  console_type TEXT NOT NULL CHECK (console_type IN ('PS5','PS4')),
  blocks INT NOT NULL CHECK (blocks >= 1),
  status TEXT NOT NULL DEFAULT 'WAITING' CHECK (status IN ('WAITING','SEATED','REFUNDED')),
  session_id BIGINT REFERENCES sessions,       -- set on seat
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (token_date, token_no)
);
ALTER TABLE bookings ADD CONSTRAINT fk_queue_entry FOREIGN KEY (queue_entry_id) REFERENCES queue_entries;
CREATE INDEX ON queue_entries (token_date, status);

-- ─────────────────────────────────────────────────────────────────────────────
-- Seed (B15): the row a fresh venue cannot boot without.
-- ─────────────────────────────────────────────────────────────────────────────

-- booking_settings is a single-row table (docs/bookings.md §1): the CHECK (id) pins the primary
-- key to TRUE, so there is exactly one row and it has to exist before the first GET. Every column
-- keeps its documented default — pre-booking on, ৳100 package fee, a 2-hour cancellation lock —
-- and Setup S10 edits them from there. Existing bookings keep the fee and cutoff they were sold
-- under, so a later edit can never reach a row written before it (docs/bookings.md §1).
INSERT INTO booking_settings (id) VALUES (TRUE);
