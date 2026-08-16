# Gamer's Den — Pre-booking & Play-queue Module

Extends the spec set; `api-contract.md` remains the authority. The prototype's Bookings page and Floor queue rail are the visual reference.

---

## 1. Feature control (Admin only)

`booking_settings` (Setup S10): `enabled` (feature flag — hides the Bookings nav item and rejects booking APIs with 409 `PREBOOKING_DISABLED`), `package_fee` (৳, added to every booking), `cancel_cutoff_hours` (cancellation lock window). Changes apply to NEW bookings only; existing bookings keep the fee and cutoff they were sold under.

## 2. Booking lifecycle

```
PAID ──check-in──► ARRIVED ──seat (Floor)──► USED
  └──cancel (≥ cutoff h before start)──► CANCELLED (full refund)
```

- **Create (pay first):** the booking is confirmed only when the full amount is taken — play time (`blocks` × 30 min at the console's rate, snapshot) **plus** the package fee. Member attach optional (search by name/phone) or free-text name + phone. One transaction (`tx_id`), posts to the seller's shift (X/Z "Pre-booking" line).
- **Cancel:** allowed only while PAID and ≥ `cancel_cutoff_hours` before `start_at`; creates a full negative refund transaction. Inside the window the UI locks the action with a note; API 409 `CANCEL_CUTOFF_PASSED`.
- **Check-in (Bookings page, not POS):** staff selects the booking row → "Check in & print token". Assigns the next daily queue token, prints P6 ("PLAY TICKET — PREBOOKED"), status → ARRIVED. The row moves from Upcoming to History (`Token #NN · waiting — seat from Floor`).
- **Seat (Floor page):** when the booked console is free its session panel offers `Seat #NN «name» · «len» prepaid`. Seating creates the session with the prepaid blocks already marked paid; the clock starts when staff presses start. Extra time is ordinary billable +30 min blocks. Ending follows the normal net-outstanding rule (prepaid counts as settled).

## 3. Play queue (walk-up tokens)

- POS sells **play tickets** (console type + length) via `POST /payments` `playTickets[]` — sellable while every console is busy. Each sale issues the next daily token and a P6 stub, and enters the queue as WAITING.
- The Floor queue rail lists WAITING entries in token order ("who plays next"); staff may seat ANY waiting entry (customer choice), not strictly FIFO — `POST /play-queue/{id}/seat {stationId}`. The session auto-loads the token's prepaid time.
- Console-type match is enforced (PS5 ticket → PS5 console) — 409 `CONSOLE_TYPE_MISMATCH`.
- No-show cleanup: Manager+ may refund & remove a waiting entry.

## 4. Tokens

One daily counter shared by bookings and play tickets, reset at venue-timezone midnight (`token_seq` keyed by date). Printed double-height as `TOKEN #NN`; barcoded (Code 128, entry id). Tokens identify *who plays next* — they are queue identity, not payment proof (the transaction is).

## 5. Schema (DDL)

```sql
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
```

`transactions.booking_amount` (core schema addition) carries booking/play-ticket revenue so the X/Z report breaks it out — reconciliation is structural, same as tournaments: no queue entry or booking without its `tx_id`, every transaction in a shift.

## 6. Reconciliation & reporting

- X/Z takings gain a "Pre-booking" line (`booking_amount` by method). Refunds are negative rows in the refunding shift.
- Overview's "Pre-sold" stat = sum of PAID bookings + WAITING play tickets (money taken for play not yet delivered).
- Reports: bookings per day, show-rate (USED / (USED+CANCELLED+expired)), package-fee income.

## 7. Edge cases

| Case | Behavior |
|---|---|
| Booking's console busy at arrival | Check-in still works (token issued); seat from Floor when free, or seat the token on another free console of the same type |
| Two bookings, same console, overlapping | Allowed with a warning at create (staff overrides); flagged in the Upcoming list |
| Day rollover with WAITING tokens | Tokens keep working (entry id is the key); display shows their issue date; counter restarts for new sales |
| Feature disabled with PAID bookings outstanding | Existing bookings remain serviceable (check-in/seat/cancel); only NEW bookings are blocked |
| Refund after check-in | Not via cancel (409 `ALREADY_CHECKED_IN`); Manager+ voids the transaction instead |
