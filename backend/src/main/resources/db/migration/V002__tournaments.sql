-- V002__tournaments.sql — tournaments, station blocks, entries, bracket (TASK B12).
--
-- DDL below is a VERBATIM copy of docs/tournaments.md §2 (TASKLIST GLOBAL RULE 4: copy, never
-- redesign), in the order that file writes it. backend/ARCHITECTURE.md §4.2 assigns this file
-- number: V001 core, V002 tournaments, V003 bookings/queue (B15).
--
-- tournament_matches ships here with the rest of the schema even though the bracket engine is
-- B13 — the migration is the document's schema, not this task's subset. Additive-only.

CREATE TABLE tournaments (
  id            BIGSERIAL PRIMARY KEY,
  name          TEXT NOT NULL UNIQUE,
  game          TEXT NOT NULL,
  cadence       TEXT NOT NULL CHECK (cadence IN ('WEEKLY','MONTHLY','ONE_OFF')),
  scheduled_at  TIMESTAMPTZ NOT NULL,
  entry_fee     INT NOT NULL CHECK (entry_fee >= 0),
  prize_pool    INT NOT NULL CHECK (prize_pool >= 0),
  max_players   INT NOT NULL DEFAULT 8 CHECK (max_players IN (4,8,16,32)),  -- 2^n only
  match_duration_min INT NOT NULL DEFAULT 20,
  status        TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','LIVE','DONE','CANCELLED')),
  winner_entry_id BIGINT,                       -- FK below
  created_by    BIGINT NOT NULL REFERENCES staff,  -- ADMIN/MANAGER, service-enforced
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  cancelled_reason TEXT
);

CREATE TABLE tournament_station_blocks (
  tournament_id BIGINT NOT NULL REFERENCES tournaments ON DELETE CASCADE,
  station_id    BIGINT NOT NULL REFERENCES stations,
  PRIMARY KEY (tournament_id, station_id)
);
-- Station is "reserved" iff listed here for a tournament with status IN ('OPEN','LIVE').
-- POST /sessions on a reserved station -> 409 STATION_RESERVED.
-- Concurrent tournaments are safe: each draws consoles only from its own block rows.

CREATE TABLE tournament_entries (               -- one row per sold ticket
  id            BIGSERIAL PRIMARY KEY,
  tournament_id BIGINT NOT NULL REFERENCES tournaments,
  member_id     BIGINT REFERENCES members,      -- NULL = walk-in
  player_name   TEXT NOT NULL,                  -- attached member's name or typed free-text
  tx_id         BIGINT NOT NULL REFERENCES transactions,  -- the POS sale (reconciliation)
  seed          INT NOT NULL,                   -- sale order; printed as TOKEN #NN
  qr_token      TEXT NOT NULL UNIQUE,           -- random 128-bit, in the ticket QR
  checked_in    BOOLEAN NOT NULL DEFAULT FALSE,
  refunded      BOOLEAN NOT NULL DEFAULT FALSE,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tournament_id, seed)
);
CREATE INDEX ON tournament_entries (tournament_id);
ALTER TABLE tournaments ADD CONSTRAINT fk_winner FOREIGN KEY (winner_entry_id) REFERENCES tournament_entries;

CREATE TABLE tournament_matches (               -- self-referencing bracket tree
  id            BIGSERIAL PRIMARY KEY,
  tournament_id BIGINT NOT NULL REFERENCES tournaments ON DELETE CASCADE,
  round         INT NOT NULL,                   -- 1 = first round
  slot          INT NOT NULL,
  entry_a       BIGINT REFERENCES tournament_entries,
  entry_b       BIGINT REFERENCES tournament_entries,
  winner_entry  BIGINT REFERENCES tournament_entries,
  next_match_id BIGINT REFERENCES tournament_matches,  -- winner advances along this link
  station_id    BIGINT REFERENCES stations,     -- assigned console while in play
  started_at    TIMESTAMPTZ,
  extra_min     INT NOT NULL DEFAULT 0,         -- added time
  decided_by    BIGINT REFERENCES staff,
  decided_at    TIMESTAMPTZ,
  UNIQUE (tournament_id, round, slot),
  CONSTRAINT winner_is_participant CHECK (winner_entry IS NULL OR winner_entry IN (entry_a, entry_b))
);
-- One console cannot host two unfinished started matches:
CREATE UNIQUE INDEX one_live_match_per_station
  ON tournament_matches (station_id) WHERE winner_entry IS NULL AND station_id IS NOT NULL;
