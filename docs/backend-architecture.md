# Gamer's Den — Backend Architecture

Stack (fixed): Java 21, Spring Boot 3.3+, PostgreSQL 16. Venue instance on the cafe PC; thin cloud instance receives one-way replication. One deployable JAR, modular monolith. `api-contract.md` is the authority; tournament detail in `tournaments.md`, booking/queue detail in `bookings.md`.

---

## 1. Modules, layers, build

- **Maven** (boring, IDE-default; rejected Gradle — no team to convince).
- One module, package-per-feature:

```
dev.gamersden/
  auth/ station/ session/ catalog/ member/ billing/
  booking/ queue/ shift/ tournament/ printing/ report/ alert/ settings/ sync/ common/
```

Each package: `web` (controllers/DTOs), `domain` (entities, services), `repo` (Spring Data JPA). No hexagonal ceremony.

Libraries: Spring Web, Data JPA, Security, Validation; Flyway; springdoc-openapi (FE type-gen); **escpos-coffee** (ESC/POS incl. QR/barcode commands; rejected raw bytes); **usb4java** (raw device; rejected OS spooler — drivers reflow thermal output); Testcontainers; jjwt.

## 2. PostgreSQL schema (DDL)

Core tables below; tournament tables in `tournaments.md` §2/§7; booking/queue tables in `bookings.md` §5.

```sql
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
```

Derived values (bill totals, remaining seconds, expected cash, match countdowns, net outstanding) are computed, never stored redundantly; transaction snapshots are immutable.

## 3. Migration strategy

Flyway. `V001__baseline.sql` = core schema + seed (Admin, pricing, categories); `V002__tournaments.sql` = tournaments.md DDL; `V003__bookings.sql` = bookings.md DDL (+ `transactions.booking_amount`, `sessions.queue_entry_id`, `token_seq`); additive-only during MVP. Same set on venue and cloud.

## 4. Printable artifact rendering — decision

**Server-side ESC/POS byte stream, rendered at job creation, stored in `print_jobs.rendered`.** Retries and reprints are byte-identical (audit-true); preview (`rendered_text`) matches paper exactly; no browser capability needed. Tournament stubs (P5), play tickets (P6) and booking confirmations (P7) render into the same job as their sale receipt; check-in renders P6 standalone. Rejected: server-side PDF (driver raster reflows thermal); client-side (duplicated layout logic, preview≠paper).

## 5. Print queue & retry model

- Single-threaded worker per device (no interleaved tickets); claims with `SELECT … FOR UPDATE SKIP LOCKED`.
- `QUEUED → PRINTING → DONE | FAILED`. Transport errors: 3 auto-attempts, 2 s backoff. Printer status via DLE EOT polling (paper-out, cover-open, offline) → FAILED with specific error + SSE + alert.
- **Mid-print failure:** FAILED(`MID_PRINT`); staff retries from S11 — full ticket reprints, attempts++. Paper duplicates acceptable at a staffed counter; silent loss is not.
- `retry` re-sends stored bytes; `reprint` renders a new job with the reprint band.
- Settle / booking create / check-in create their print jobs inside the same DB transaction as the money write — a replayed request returns the same `printJobId`; double-print impossible.
- `receipt_copies = 2` (terminal setting) emits the copy inside the same job, after the cut.

## 6. Domain invariants (service-enforced)

- Session end requires net outstanding = 0: `sum(unpaid session_blocks) + unsettled cart = 0`. Prepaid (booking/ticket) blocks are born paid.
- Seating a token: one transaction inserts the session, its prepaid blocks (paid), flips the queue entry to SEATED, and (for bookings) flips the booking to USED.
- Booking cancel checks `now() <= start_at − cutoff_hours` (snapshot value) and status PAID.
- Token allocation goes through `token_seq` — unique per day even under concurrent sales.
- Station reserved by a live tournament refuses walk-in sessions; console-type match enforced on seat.

## 7. Validation, exceptions, logging

Jakarta `@Valid` + domain checks in services; DB CHECKs are the last line. One `@RestControllerAdvice` → error envelope. SLF4J/Logback JSON in prod; every request logs method, path, staffId, traceId. Money mutations, print jobs, winner recordings, booking state changes log INFO with entity ids. PINs never logged; payment refs last-4 only.

## 8. Environment config & secrets

Profiles: `venue` (USB printing, sync-push), `cloud` (no printing, sync-receive), `dev`, `test`. Secrets via env vars (`JWT_SECRET`, `DB_PASSWORD`, `BKASH_*`, `NAGAD_*`, `SYNC_TOKEN`); venue box uses a `.env` read by the service wrapper (WinSW/systemd, auto-restart); nightly `pg_dump` to cloud bucket in addition to outbox sync.

## 9. Sync (venue → cloud)

Transactional outbox: every committed money/inventory/tournament/booking mutation inserts a `sync_outbox` row in the same transaction. A 30 s pusher batches unpushed ops to cloud `POST /sync/push` (idempotent by op id). One-way; single writer, no conflicts.

## 10. Testing

- **Unit:** session state machine incl. prepaid blocks; block pricing incl. morning-window boundary; net-outstanding end guard; split validation; points math; booking cutoff math (boundary: exactly cutoff hours); token_seq day rollover; bracket generation and winner propagation; finance formulas; ESC/POS golden files (bytes + text) incl. P5/P6 token + QR/barcode.
- **Integration (Testcontainers):** settle end-to-end in one transaction; idempotent replay (payments, bookings, check-in); booking create → cancel refund → check-in → seat → end without payment; play-ticket sale → seat with type mismatch rejected; void reversal; tournament cancel auto-refunds; shift close totals incl. tournament + booking lines; outbox push.
- **Printer:** hardware smoke script + fake `PrinterPort` in CI (offline / paper-out / mid-print).

## 11. Cross-cutting test matrix

| Case | Expected |
|---|---|
| Successful print | DONE ≤ 3 s; paper matches preview; audit complete |
| Printer offline at settle / check-in | Money write succeeds; job FAILED after retries; alert; S11 retry prints original bytes |
| Out of paper mid-print | FAILED(`PAPER_OUT`/`MID_PRINT`); retry prints full ticket; attempts++ |
| Duplicate request | Same Idempotency-Key → one transaction, one job, one booking/entry/token; replay header |
| Reprint | New job, reason stored, band printed, original linked; 400 without reason |
| Concurrent token sales | Distinct sequential tokens (row-locked token_seq) |
| Cloud down a day | Venue fully operational incl. bookings and tournaments; outbox drains on reconnect |

## 12. Open flags

- bKash/Nagad merchant onboarding unscheduled — phase-2 specced, not built; manual TrxID is MVP truth.
- Printer model unconfirmed (80mm ESC/POS assumed; 58mm config).
- Cut line if the timeline is tight: Reports charts (keep summary numbers), alerts feed (keep discrepancy alert), cloud sync (nightly dump only), tournament finance panel, booking overlap warnings (keep create + cancel + check-in).
