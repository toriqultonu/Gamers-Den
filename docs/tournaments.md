# Gamer's Den — Tournament Management Module

Extends the spec set; `api-contract.md` remains the authority. Queue tokens for pre-bookings and play tickets are a separate system (`bookings.md`) — tournament seed tokens below are per-tournament, not the daily counter. The prototype's Tournaments tab is the visual reference. This file consolidates the base module and the advanced (v2) behavior into one spec.

---

## 1. RBAC (strict, API-enforced)

| Capability | Admin | Manager | Cashier |
|---|---|---|---|
| Create / edit / cancel tournaments; set fee, prize, cap, match duration, schedule | ✓ | ✓ | ✗ 403 |
| Block / unblock stations for an event | ✓ | ✓ | ✗ |
| Generate bracket manually; decide un-started matches | ✓ | ✓ | ✗ |
| Sell entry at POS (register player, take fee, print QR+token ticket) | ✓ | ✓ | ✓ |
| Start a pending match (console auto-assign); add extra time | ✓ | ✓ | ✓ |
| Record the winner of a **started** match | ✓ | ✓ | ✓ (execution, not configuration) |
| View live bracket, match timers, history | ✓ | ✓ | ✓ read-only |
| Finance analytics | ✓ | ✓ | ✗ — separate endpoint, 403 |

Three enforcement layers: nav visibility (cosmetic) → route guard → API 403 (authoritative). Every result records `decided_by`.

## 2. Database schema (DDL)

```sql
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
```

`transactions.tournament_amount` (core schema) carries the entry-fee portion; the X/Z report breaks it out as its own takings line. **Reconciliation is structural:** an entry cannot exist without its `tx_id`, and every transaction belongs to a `shift_id` — the seller's drawer expectation includes tournament sales by the same query as all takings.

## 3. Bracket rules

- `max_players` restricted to 2ⁿ (UI chips 4/8/16/32) → **perfect brackets, exactly N−1 matches, no byes**.
- **Auto-generate:** the sale that fills the last slot generates the bracket in the same transaction (seeds = sale order) and flips status to LIVE.
- **Manual generate** (Manager+): for undersubscribed events; byes auto-advance. 409 `NOT_ENOUGH_PLAYERS` (<2).
- Winner propagation follows `next_match_id`; the final match sets `winner_entry_id`, status DONE, and releases station blocks. Cancel also releases blocks and auto-creates refund transactions (negative, normal shift rules).

## 4. Match execution & timers

- `POST /tournaments/{id}/matches/{mid}/start` (any role): assigns the first allocated console that is neither hosting an unfinished match **nor busy with a walk-in session**; stamps `started_at`; pushes match duration to that station (SSE `station-update`). 409 `NO_FREE_CONSOLE`.
- **Floor integration:** a reserved console with a started match shows the match countdown on the Floor like a regular session — players, `remainingSeconds = (duration + extra_min)·60 − elapsed`, "match over" past zero. Reserved consoles without a match show "Reserved · tournament".
- `POST /tournaments/{id}/matches/{mid}/extend` (any role): `{minutes}` — adds time; board, bracket tag, "Now on" tile and floor timer all re-base.
- Winner recording on a started match: any role (the response includes `suggestedStationId` for the advanced player's next match). Un-started matches: Manager+.
- Live surfaces, all fed by one server clock: "Now on «console»" tiles (big countdown, accent TIME UP state), bracket match tags (`console · mm:ss`), match board rows (time left / "time up — record the winner"), floor cards.
- Cashier job board: `GET /tournaments/{id}/matches?pending=true` — ready matches + console availability ("Allocated console busy with a walk-in session" when applicable).

## 5. POS integration

- Menu gains a **Tournament** category: one card per OPEN tournament (fee, slots left; full = disabled).
- The bill shows a **player name** field when an entry is in the cart — an attached member auto-fills it; otherwise free text; else "Walk-in guest".
- Settle (single `POST /payments` with `tournamentEntries[]`) registers the player, assigns the next seed, and returns `entryTokens` — printed as **TOKEN #NN** on the P5 stub with the QR (`qr_token`). Multiple entries → one token each.
- 409 `TOURNAMENT_FULL`, `TOURNAMENT_NOT_OPEN`. Idempotency-Key prevents double-registration/printing.

## 6. Finance analytics (Manager/Admin only)

`GET /tournaments/{id}/finance` — 403 for cashier tokens; never embedded in shared payloads:

- `revenue = entries × entry_fee`
- `netProfit = revenue − prize_pool`
- `opportunityCost = (N−1) × match_duration_min/60 × avgHourlyRate(allocated stations)`
- `extraMargin = netProfit − opportunityCost`

Surfaced in the manager rail as four stats plus the verdict line, e.g. "This tournament generates ৳2,500 extra compared to standard hourly rentals" (or the negative phrasing).

## 7. Print template P5 — Tournament ticket

See `design.md` §5 P5: inverted TOURNAMENT ENTRY band, tournament name, player name, **TOKEN #NN double-height**, QR (model 2, ECC M, module ≥0.5mm, quiet zone 4, content = opaque `qr_token`, native `GS ( k`), footer, reprint band rules as P1. Appended to the sale receipt in the same print job; standalone on reprint. `POST /tournament-entries/{id}/check-in` (`{qrToken}`) marks arrival; 409 `ALREADY_CHECKED_IN`.

## 8. UI structure (S12)

- Tabs: *Live & upcoming* / *History* (winners, prizes, entries by date).
- Left rail: tournament cards (status tag Live/Registration/Done, fee, slots).
- Main: "Now on" live tiles → champion banner (when done) → bracket columns (Round of 16 → Final; Winner ✓ chips on decidable rows, red W on winners, dimmed losers) → match board (start / +5 min) — or, pre-bracket, the registered-player list with seeds and slots-left note.
- Right rail Manager+: selected tournament (station-block chips, cancel, finance panel) + arrange form (name, game, when, weekly/monthly, 2ⁿ cap chips, fee, prize, match minutes, station blocks).
- Right rail Cashier: read-only guidance + "Sell an entry at the POS" (deep-links POS in counter mode, Tournament category).
