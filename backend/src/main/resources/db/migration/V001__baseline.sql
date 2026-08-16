-- V001__baseline.sql — Gamer's Den core schema (TASK B02).
--
-- DDL below is a VERBATIM copy of docs/backend-architecture.md §2 (TASKLIST GLOBAL RULE 4:
-- copy, never redesign). Per backend/ARCHITECTURE.md §4.2 the booking-era columns
-- (transactions.booking_amount, sessions.queue_entry_id, token_seq) and the
-- PLAY_TICKET / BOOKING_CONFIRMATION print-job types ship here so V001 stays that verbatim copy;
-- the booking/queue TABLES themselves arrive in V003 (B15) and the tournament tables in V002 (B12).
-- Additive-only during MVP. Schema is owned by Flyway — never ddl-auto beyond validate.

CREATE TABLE staff (
  id BIGSERIAL PRIMARY KEY,
  name TEXT NOT NULL UNIQUE,
  role TEXT NOT NULL CHECK (role IN ('ADMIN','MANAGER','CASHIER')),
  pin_hash TEXT NOT NULL,                      -- BCrypt
  avatar_color TEXT,                           -- profile pref (S13)
  active BOOLEAN NOT NULL DEFAULT TRUE,
  failed_pins INT NOT NULL DEFAULT 0,
  locked_until TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE terminal_settings (               -- S13, one row per terminal
  terminal TEXT PRIMARY KEY,
  theme TEXT NOT NULL DEFAULT 'DARK' CHECK (theme IN ('DARK','LIGHT')),
  font_scale TEXT NOT NULL DEFAULT 'DEFAULT' CHECK (font_scale IN ('COMPACT','DEFAULT','LARGE')),
  accent TEXT NOT NULL DEFAULT '#ec3013',
  login_bg BYTEA,                              -- admin-uploaded image (or object-store id)
  sound BOOLEAN NOT NULL DEFAULT TRUE,
  auto_lock_min INT NOT NULL DEFAULT 5,        -- 0 = off
  receipt_copies INT NOT NULL DEFAULT 1 CHECK (receipt_copies IN (1,2)),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE stations (
  id BIGSERIAL PRIMARY KEY,
  name TEXT NOT NULL UNIQUE,
  console_type TEXT NOT NULL CHECK (console_type IN ('PS5','PS4')),
  status TEXT NOT NULL DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE','MAINTENANCE')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE pricing (
  console_type TEXT PRIMARY KEY CHECK (console_type IN ('PS5','PS4')),
  per_hour INT NOT NULL, per_half_hour INT NOT NULL,
  morning_discount_pct INT NOT NULL DEFAULT 25,
  morning_start TIME NOT NULL DEFAULT '10:00', morning_end TIME NOT NULL DEFAULT '14:00',
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE members (
  id BIGSERIAL PRIMARY KEY,
  name TEXT NOT NULL, phone TEXT NOT NULL UNIQUE,
  preferred_console TEXT, games TEXT[],
  wallet INT NOT NULL DEFAULT 0 CHECK (wallet >= 0),
  points INT NOT NULL DEFAULT 0 CHECK (points >= 0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE shifts (
  id BIGSERIAL PRIMARY KEY,
  staff_id BIGINT NOT NULL REFERENCES staff,
  terminal TEXT NOT NULL DEFAULT 'T1',
  opening_float INT NOT NULL,
  opened_at TIMESTAMPTZ NOT NULL DEFAULT now(), closed_at TIMESTAMPTZ,
  counted_cash INT, expected_cash INT, discrepancy INT, handover_note TEXT
);
CREATE UNIQUE INDEX one_open_shift_per_terminal ON shifts (terminal) WHERE closed_at IS NULL;

CREATE TABLE sessions (
  id BIGSERIAL PRIMARY KEY,
  station_id BIGINT NOT NULL REFERENCES stations,
  member_id BIGINT REFERENCES members,
  shift_id BIGINT NOT NULL REFERENCES shifts,
  queue_entry_id BIGINT,                       -- set when seated from a token (booking or play ticket)
  state TEXT NOT NULL DEFAULT 'OPEN' CHECK (state IN ('OPEN','RUNNING','PAUSED','LOCKED','CLOSED')),
  consumed_sec INT NOT NULL DEFAULT 0,
  running_since TIMESTAMPTZ,                   -- set iff RUNNING
  started_at TIMESTAMPTZ NOT NULL DEFAULT now(), ended_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX one_live_session_per_station ON sessions (station_id) WHERE state <> 'CLOSED';

CREATE TABLE session_blocks (                  -- one row per 30-min block
  id BIGSERIAL PRIMARY KEY,
  session_id BIGINT NOT NULL REFERENCES sessions,
  price INT NOT NULL,                          -- snapshot (morning rate applied at purchase)
  paid_tx_id BIGINT,                           -- NULL until settled; prepaid blocks reference the
                                               -- booking/ticket sale tx from creation
  removed BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ON session_blocks (session_id) WHERE NOT removed;
-- Seating a token inserts its prepaid blocks with paid_tx_id = the original sale tx:
-- the end-session guard (net outstanding = unpaid blocks + cart − 0) passes without a second payment.

CREATE TABLE items (
  id BIGSERIAL PRIMARY KEY,
  name TEXT NOT NULL UNIQUE,
  category TEXT NOT NULL CHECK (category IN ('BEVERAGE','FOOD','SNACK','EXTRAS')),
  price INT NOT NULL, stock INT NOT NULL DEFAULT 0, reorder_at INT NOT NULL DEFAULT 0,
  active BOOLEAN NOT NULL DEFAULT TRUE
);
-- Tournament entries and play tickets are NOT items rows — they are first-class
-- payment lines (tournamentEntries[] / playTickets[]) priced from their own tables.

CREATE TABLE stock_movements (
  id BIGSERIAL PRIMARY KEY,
  item_id BIGINT NOT NULL REFERENCES items,
  delta INT NOT NULL,
  reason TEXT NOT NULL CHECK (reason IN ('SALE','VOID','MANUAL_ADJUST','INITIAL')),
  ref_tx_id BIGINT, staff_id BIGINT NOT NULL REFERENCES staff,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE carts (
  id BIGSERIAL PRIMARY KEY,
  session_id BIGINT UNIQUE REFERENCES sessions, -- NULL => counter cart
  settled BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE cart_lines (
  cart_id BIGINT NOT NULL REFERENCES carts,
  item_id BIGINT NOT NULL REFERENCES items,
  qty INT NOT NULL CHECK (qty > 0),
  unit_price INT NOT NULL,
  PRIMARY KEY (cart_id, item_id)
);

CREATE TABLE transactions (
  id BIGSERIAL PRIMARY KEY,
  public_id TEXT NOT NULL UNIQUE,              -- 'GD-2608-047' (printed/barcoded)
  session_id BIGINT REFERENCES sessions, cart_id BIGINT REFERENCES carts,
  member_id BIGINT REFERENCES members,
  shift_id BIGINT NOT NULL REFERENCES shifts, staff_id BIGINT NOT NULL REFERENCES staff,
  gaming_amount INT NOT NULL DEFAULT 0,
  fnb_amount INT NOT NULL DEFAULT 0,
  tournament_amount INT NOT NULL DEFAULT 0,    -- X/Z tournament line
  booking_amount INT NOT NULL DEFAULT 0,       -- X/Z pre-booking line (bookings + play tickets)
  points_redeemed INT NOT NULL DEFAULT 0, points_earned INT NOT NULL DEFAULT 0,
  total_due INT NOT NULL,                      -- negative for refunds
  voided BOOLEAN NOT NULL DEFAULT FALSE, void_reason TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ON transactions (shift_id); CREATE INDEX ON transactions (created_at);

CREATE TABLE payment_splits (
  id BIGSERIAL PRIMARY KEY,
  tx_id BIGINT NOT NULL REFERENCES transactions,
  method TEXT NOT NULL CHECK (method IN ('CASH','BKASH','NAGAD','WALLET')),
  amount INT NOT NULL CHECK (amount <> 0),     -- negative on refunds
  payment_ref TEXT,
  verify_state TEXT NOT NULL DEFAULT 'MANUAL' CHECK (verify_state IN ('MANUAL','PENDING','VERIFIED','FAILED'))
);

CREATE TABLE points_ledger (
  id BIGSERIAL PRIMARY KEY,
  member_id BIGINT NOT NULL REFERENCES members,
  delta INT NOT NULL,
  kind TEXT NOT NULL CHECK (kind IN ('EARN','REDEEM_BILL','REDEEM_WALLET','REVERSAL')),
  ref_tx_id BIGINT, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE wallet_ledger (LIKE points_ledger INCLUDING ALL);
ALTER TABLE wallet_ledger DROP CONSTRAINT IF EXISTS points_ledger_kind_check;
ALTER TABLE wallet_ledger ADD CONSTRAINT wallet_kind_check
  CHECK (kind IN ('TOPUP','SPEND','POINTS_CONVERSION','REVERSAL'));

CREATE TABLE expenses (
  id BIGSERIAL PRIMARY KEY,
  shift_id BIGINT NOT NULL REFERENCES shifts, staff_id BIGINT NOT NULL REFERENCES staff,
  description TEXT NOT NULL,
  category TEXT NOT NULL CHECK (category IN ('SUPPLIES','UTILITIES','REPAIRS','STAFF','OTHER')),
  amount INT NOT NULL CHECK (amount > 0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE print_jobs (
  id BIGSERIAL PRIMARY KEY,
  type TEXT NOT NULL CHECK (type IN ('RECEIPT','Z_REPORT','X_REPORT','EXPENSE_VOUCHER',
                                     'TOURNAMENT_STUB','PLAY_TICKET','BOOKING_CONFIRMATION','TEST')),
  ref_id BIGINT NOT NULL,
  status TEXT NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED','PRINTING','DONE','FAILED')),
  attempts INT NOT NULL DEFAULT 0,
  device_id TEXT NOT NULL, operator_id BIGINT NOT NULL REFERENCES staff,
  is_reprint BOOLEAN NOT NULL DEFAULT FALSE,
  reprint_reason TEXT CHECK (reprint_reason IN ('LOST','DAMAGED','CUSTOMER_COPY','DISPUTE')),
  original_job_id BIGINT REFERENCES print_jobs,
  rendered BYTEA NOT NULL,                     -- final ESC/POS bytes
  rendered_text TEXT NOT NULL,                 -- 48-col preview
  error TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(), completed_at TIMESTAMPTZ,
  CONSTRAINT reprint_needs_reason CHECK (NOT is_reprint OR reprint_reason IS NOT NULL)
);
CREATE INDEX ON print_jobs (status) WHERE status IN ('QUEUED','FAILED');

CREATE TABLE token_seq (                       -- daily queue-token counter
  token_date DATE PRIMARY KEY,
  next_no INT NOT NULL DEFAULT 1
);
-- allocate with: UPDATE token_seq SET next_no = next_no + 1 WHERE token_date = $today RETURNING next_no - 1
-- (upsert the row first); serialized by row lock, resets by keying on date.

CREATE TABLE alerts (
  id BIGSERIAL PRIMARY KEY,
  type TEXT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL,
  read BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE idempotency_keys (
  key UUID PRIMARY KEY,
  request_hash TEXT NOT NULL, response_body JSONB NOT NULL, status_code INT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE sync_outbox (
  id BIGSERIAL PRIMARY KEY,
  aggregate TEXT NOT NULL, op JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(), pushed_at TIMESTAMPTZ
);
CREATE INDEX ON sync_outbox (id) WHERE pushed_at IS NULL;

-- ─────────────────────────────────────────────────────────────────────────────
-- Seed (B02): the rows a fresh venue cannot boot without.
-- ─────────────────────────────────────────────────────────────────────────────

-- Bootstrap Admin. PIN 1234, BCrypt ($2a$, strength 10) — the venue MUST change it on
-- first login (staff CRUD lands in B03). PINs are never logged (ARCHITECTURE.md §5.12).
INSERT INTO staff (name, role, pin_hash, avatar_color)
VALUES ('Admin', 'ADMIN', '$2a$10$vQw5cb3Q8ayaEYS3AhxxLubAXCM1MYh262q9ClSEUZEySpBRTe7M6', '#ec3013');

-- Console rates (BDT, integer money). morning_discount_pct / morning_start / morning_end keep
-- their column defaults 25% / 10:00-14:00 — the documented default for the OPEN FLAG in
-- ARCHITECTURE.md §8; confirm with the venue before treating them as final.
INSERT INTO pricing (console_type, per_hour, per_half_hour) VALUES
  ('PS5', 120, 80),
  ('PS4',  80, 50);

-- Item categories are the CHECK constraint on items.category — BEVERAGE / FOOD / SNACK / EXTRAS.
-- They are an enum, not a table, so there is nothing to insert here; the catalogue itself is
-- venue data (entered in S5, demo rows come from the dev-profile seed in B22).
