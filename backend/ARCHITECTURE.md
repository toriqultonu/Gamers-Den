# Gamer's Den Backend — Execution Architecture

Companion to `docs/backend-architecture.md` (design rationale), `docs/api-contract.md` (**the authority** for endpoints, DTOs, error codes) and `docs/tournaments.md` (tournament DDL + rules).
This file is the **build-time reference**: canonical names, layering rules, and gates every task in `TASKLIST.md` must obey. Edit here first; task texts reference this file.

---

## 1. Context

Single-venue console-gaming-cafe POS. One Spring Boot JAR runs **on the cafe PC** (profile `venue`: owns the USB thermal printer, pushes sync) and the same JAR runs in the cloud (profile `cloud`: no printing, receives sync). PostgreSQL 16 on both. One engineer, one month — modular monolith, no ceremony.

**Money path (the spine everything hangs off):**
`session blocks / cart lines / tournament entries → GET bill → POST /payments (one DB TX: splits validated, blocks marked paid, stock decremented, ledgers written, entries seeded, transaction snapshot, print job rendered+queued) → print worker → Z-report at shift close`

## 2. Stack (fixed — do not re-litigate)

| Concern | Choice |
|---|---|
| Runtime | Java 21, Spring Boot 3.3+, Maven, single module |
| DB | PostgreSQL 16, Spring Data JPA, Flyway (additive-only in MVP) |
| Auth | jjwt — PIN login → JWT access (15 min) + rotating refresh cookie (12 h); BCrypt PIN hashes |
| Printing | **escpos-coffee** (ESC/POS render incl. `GS k` barcode / `GS ( k` QR) + **usb4java** (raw USB; never the OS spooler) |
| Live updates | SSE (`/events`), no WebSocket |
| API docs | springdoc-openapi — FE type-gen consumes `/v3/api-docs`; breaking drift fails FE CI |
| Tests | JUnit 5, Testcontainers (PG), fake `PrinterPort` in CI |

## 3. Package layout (canonical)

```
dev.gamersden/
  common/       error envelope, ApiException + codes, idempotency filter, money/time utils, SSE hub
  auth/         login/refresh/logout, staff CRUD, PIN lockout, role guards, /me/prefs
  station/      stations CRUD, pricing
  session/      session state machine, blocks, clock
  catalog/      items, stock_movements, carts, cart_lines
  member/       members, wallet_ledger, points_ledger
  billing/      bill computation, payments/settle, void, transactions
  shift/        shifts, X/Z computation, expenses
  tournament/   tournaments, entries, bracket, matches, finance, check-in
  printing/     render (P1–P5), print_jobs, queue worker, device port, printers
  report/       reports + overview aggregates
  alert/        alerts table + feed
  settings/     terminal_settings, login-bg upload
  sync/         sync_outbox, pusher (venue), receiver (cloud)
```

Each package: `web/` (controllers + DTOs) → `domain/` (entities, services) → `repo/` (Spring Data). No hexagonal ceremony, but **no cross-package repo access** — call the owning package's service.

## 4. Canonical names — tables, endpoints, codes, events

### 4.1 Table → owning package

| Tables | Owner |
|---|---|
| `staff` | auth |
| `stations`, `pricing` | station |
| `sessions`, `session_blocks` | session |
| `items`, `stock_movements`, `carts`, `cart_lines` | catalog |
| `members`, `wallet_ledger`, `points_ledger` | member |
| `transactions`, `payment_splits` | billing |
| `shifts`, `expenses` | shift |
| `tournaments`, `tournament_station_blocks`, `tournament_entries`, `tournament_matches` | tournament |
| `print_jobs` | printing |
| `alerts` | alert |
| `terminal_settings` | settings |
| `idempotency_keys` | common |
| `sync_outbox` | sync |

DDL is written in `docs/backend-architecture.md` §2 and `docs/tournaments.md` §2 — copy it verbatim into migrations, do not redesign columns.

### 4.2 Flyway numbering

`V001__baseline.sql` (core schema + seed: Admin staff, PS5/PS4 pricing, item categories) · `V002__tournaments.sql` (tournaments.md DDL). Subsequent migrations numbered sequentially; **additive-only** during MVP. Never `ddl-auto` beyond `validate`.

### 4.3 Endpoint → package (base `/api/v1`)

Full contract in `docs/api-contract.md` §2 — that file wins on any conflict. Map:

- **auth** — `POST /auth/login|refresh|logout`, `GET/POST /staff`, `PATCH/DELETE /staff/{id}`, `GET/PUT /me/prefs`
- **station** — `GET/POST /stations`, `PATCH/DELETE /stations/{id}`, `GET/PUT /pricing`, `/pricing/{consoleType}`
- **session** — `POST /sessions`, `POST /sessions/{id}/blocks|clock|end`, `GET /sessions/{id}`, `GET /sessions?active=true`
- **catalog** — `/items…` CRUD, `POST /carts`, `PUT /carts/{id}/lines`
- **member** — `GET /members?q=`, `POST /members`, `GET /members/{id}`, `POST /members/{id}/wallet/topup|redeem-points`
- **billing** — `GET /sessions/{id}/bill`, `POST /payments`, `POST /payments/{id}/void`, `GET /transactions`
- **shift** — `POST /shifts`, `GET /shifts/current/x-report`, `POST /shifts/current/close`, `GET /shifts`, `GET/POST /expenses`
- **tournament** — everything under `/tournaments…` + `POST /tournament-entries/{id}/check-in` (detail: `docs/tournaments.md`)
- **settings** — `GET/PUT /terminal-settings`, `POST /terminal-settings/login-bg`
- **printing** — `/print-jobs…`, `GET /printers`, `PUT /printers/default`
- **report** — reports/overview aggregate endpoints (shapes chosen to match design.md S2/S9 tiles)
- **common/sync** — `GET /events` (SSE), `GET /sync/status`, cloud `POST /sync/push`

### 4.4 Error envelope + domain codes

One `@RestControllerAdvice` renders `{ "error": { code, message, details, traceId } }` for every non-2xx. Standard codes: `VALIDATION_FAILED` 400, `UNAUTHORIZED` 401, `FORBIDDEN` 403, `NOT_FOUND` 404, `CONFLICT`/`IDEMPOTENCY_REPLAY` 409, `LOCKED_PIN` 423, `RATE_LIMITED` 429, `PRINTER_UNAVAILABLE`/`SYNC_UNAVAILABLE` 503.

Domain 409 codes (canonical spellings — FE switches on them): `STATION_BUSY`, `STATION_RESERVED`, `STATION_IN_USE`, `BLOCKS_CONSUMED`, `NO_BLOCKS`, `SESSION_HAS_BALANCE`, `OUT_OF_STOCK`, `DUPLICATE_NAME`, `DUPLICATE_PHONE`, `INSUFFICIENT_POINTS`, `SPLIT_MISMATCH`, `WALLET_INSUFFICIENT`, `PAYMENT_REF_REQUIRED`, `SHIFT_ALREADY_OPEN`, `STAFF_ON_SHIFT`, `TOURNAMENT_FULL`, `TOURNAMENT_NOT_OPEN`, `NOT_ENOUGH_PLAYERS`, `NO_FREE_CONSOLE`, `ALREADY_CHECKED_IN`.

### 4.5 SSE events (`GET /events`)

`station-update` (sessions AND tournament match timers) · `tournament-update` · `alert` · `printer-status` · `sync-status`. Emitted from services after commit via the `common` SSE hub. FE polls every 10 s as fallback — payloads must equal the corresponding GET shapes.

### 4.6 Roles

`ADMIN` > `MANAGER` > `CASHIER`. The permission matrix in `docs/api-contract.md` §1 is API-enforced (`@PreAuthorize` per endpoint); UI hiding is cosmetic. Tournament nuance: cashiers may sell entries, start matches, extend, and record winners of **started** matches; configuration + finance is Manager+.

## 5. Invariants the code must keep true

1. **Server-side time.** Block math, `remainingSeconds`, match countdowns computed from DB timestamps; clients only render. Never trust client clocks.
2. **Idempotency.** `Idempotency-Key` required on `POST /payments`, `/print-jobs`, `/sessions/*/blocks`, `/wallet/*`, `/tournaments/*/entries`. Stored 48 h with first response; identical retry → replay + `Idempotency-Replayed: true`; same key different body → 409. A retried settle can never double-charge, double-register, or double-print.
3. **Settle is one DB transaction** — including print-job render+insert and tournament entry registration. Replay returns the same `transactionId`/`printJobId`/`entryTokens`.
4. **Derived values are never stored** (bill totals, expected cash, remaining seconds); transaction snapshots are immutable.
5. **Print bytes are rendered once** at job creation into `print_jobs.rendered` (+ 48-col `rendered_text`); retry re-sends stored bytes; reprint = new job with reason + band.
6. **Bracket integrity.** 2ⁿ caps → exactly N−1 matches, no byes on auto-generate; winner follows `next_match_id`; one live match per console (partial unique index); console assignment skips walk-in-busy stations.
7. **Reconciliation is structural.** Every entry has a `tx_id`; every transaction a `shift_id`; `transactions.tournament_amount` feeds the X/Z tournament line with the same query as all takings.
8. **Outbox in the mutating TX.** Money/inventory/tournament mutations insert a `sync_outbox` row in the same transaction; 30 s pusher, idempotent by op id, one-way venue → cloud.
9. **Logging:** JSON logs; every request logs method/path/staffId/traceId; money+print+winner mutations at INFO with ids. PINs never logged; payment refs last-4 only.

## 6. Profiles & config

`venue` (USB printing on, sync-push on) · `cloud` (printing off, sync-receive on) · `dev` (fake printer port, seeded data) · `test`. Secrets via env vars: `JWT_SECRET`, `DB_PASSWORD`, `SYNC_TOKEN`, (phase-2 `BKASH_*`, `NAGAD_*`). Timezone `Asia/Dhaka`; money is integer BDT.

## 7. Testing gates (every task)

- Unit tests for domain logic; **Testcontainers** for anything touching Postgres.
- ESC/POS output locked with **golden files** (bytes + rendered text) per template.
- `mvn verify` green = task done. Red build never merges.
- The e2e money-path integration test (B20) is the release gate: session → blocks → bill → split settle incl. tournament entry → print job → shift close totals.

## 8. Open flags (do not guess — ask)

Printer model (80mm/203dpi assumed, 58mm is config) · bKash/Nagad merchant credentials (MVP = manual TrxID) · morning-discount hours (default 10:00–14:00, −25%). Cut line if the month runs tight: report charts (keep summary numbers), alerts feed (keep discrepancy alert), cloud sync (nightly dump only), tournament finance panel.
