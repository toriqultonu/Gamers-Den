# Gamer's Den — Execution Task List

Granular task list to build the whole app with Claude Code. **1 TASK = 1 Claude Code session = 1 prompt.**
Tasks are ordered by dependency — backend `B01…B22` first, frontend `F01…F17` after (FE can start at F01 once B01–B04 exist, since types generate from the backend OpenAPI). Each task ends with a green build, so errors never accumulate.

- **Source of truth:** `docs/design.md` (screens/states/tokens/prints) → `docs/api-contract.md` (**wins all conflicts** on shapes) → `docs/tournaments.md` (tournament rules/DDL) → `docs/bookings.md` (booking/queue rules/DDL) → `backend/ARCHITECTURE.md` + `frontend/ARCHITECTURE.md` (canonical names, invariants) → this file (execution order + prompts).
- **Scope:** 39 tasks ≈ 1 engineer × 5–6 weeks. Heavy tasks marked 🔴 (budget 1.5–2 sessions/days).
- **Repos:** code lives in `backend/` (Spring Boot, Maven) and `frontend/` (Next.js). Visual reference: `Gamers Den.dc.html`.

---

## How to prompt Claude Code so it builds without errors

### 1. One task per session
Never bundle tasks. Start each in a fresh session (`/clear` first). Smaller scope = fewer compile errors = easier review.

### 2. Per-task prompts
Every task below carries its own ready-to-paste **Prompt**. The scope/done-when lives in this file — Claude reads it; don't re-type details.

### 3. GLOBAL RULES (Claude must obey every task)
1. **Authority order:** `api-contract.md` wins on shapes; `design.md` wins on UI/states/prints; `tournaments.md` wins on bracket rules; `bookings.md` wins on booking/queue rules. Never invent an endpoint, field, error code, table, or token — canonical names live in the two `ARCHITECTURE.md` files (§4 each).
2. **Backend layering:** package-per-feature `web/ → domain/ → repo/`; no cross-package repository access — call the owning package's service.
3. **Frontend discipline:** server components by default; timers/forms/subscriptions are client components; server state in TanStack Query, client state only in the one Zustand store; radius 0, 2px rules, tabular-nums.
4. **Every new table → a Flyway migration** (`V00x__*.sql`), DDL copied verbatim from the docs. Never `ddl-auto` beyond `validate`.
5. **Idempotency + one-TX settle are sacred:** money/print mutations (payments, bookings, cancel-refunds, play tickets, print jobs, blocks, wallet, entries) take `Idempotency-Key`; settle writes everything (payment, blocks, stock, ledgers, entries, queue tokens, print job) in one DB transaction. Booking create and check-in follow the same one-TX rule.
6. **Server time only:** all countdowns derive from server timestamps / `remainingSeconds`; clients never trust the local clock. Daily queue tokens roll over at venue-timezone midnight, server-side.
7. **Every task adds tests:** unit for logic; Testcontainers for anything touching Postgres; golden files for ESC/POS bytes; state-table assertions for screens.
8. **Build gate:** backend `mvn verify`, frontend `npm run build && npm test`. Green or not done. Paste the output.
9. **Stop-and-ask:** printer model, bKash/Nagad credentials, and morning-discount hours are OPEN FLAGS — do not guess; use the documented defaults and flag it.
10. **Don't touch other packages/screens** unless the task says so. If you need a change in `common`/`lib`, call it out.

### 4. After each task
`git checkout -b <branch from the task>` → implement → build green → commit → push → merge to `dev`. One branch per task keeps history clean and reviewable.

### 5. If a build breaks mid-task
Tell Claude: *"build fails: <paste error>. Fix without changing scope."* Don't move on with a red build — that's how errors compound.

---

# BACKEND (`backend/`)

## PHASE B0 — Foundation (Week 1)

### B01 — Scaffold + common kernel
`branch: task/B01-scaffold-common`
**Description:** Create the Maven Spring Boot 3.3+ / Java 21 project in `backend/` with the package-per-feature skeleton (`backend/ARCHITECTURE.md` §3, incl. `booking/` and `queue/`), profiles `venue|cloud|dev|test`, the error envelope (`@RestControllerAdvice` → `{error:{code,message,details,traceId}}` with the standard codes), `ApiException` hierarchy, JSON logging with traceId, `Asia/Dhaka` timezone config, actuator health, springdoc-openapi, and Testcontainers wiring. No business logic. **Blocks everything.**
**Prompt:**
```
Read backend/ARCHITECTURE.md (esp. §3–§5) and docs/api-contract.md §1.
Implement TASK B01 from TASKLIST.md: scaffold + common kernel.
Follow the GLOBAL RULES in TASKLIST.md.
Don't write test code.
Done when: mvn verify is green AND /actuator/health + /v3/api-docs return 200 AND a thrown ApiException renders the error envelope (unit test).
Show me the build output before saying you're finished.
```

### B02 — Flyway baseline schema + seed
`branch: task/B02-flyway-baseline`
**Description:** `V001__baseline.sql` with the **verbatim** core DDL from `docs/backend-architecture.md` §2 (staff, terminal_settings, stations, pricing, members, shifts, sessions incl. `queue_entry_id`, session_blocks, items, stock_movements, carts, cart_lines, transactions incl. `booking_amount`, payment_splits, points_ledger, wallet_ledger, expenses, print_jobs incl. the `PLAY_TICKET`/`BOOKING_CONFIRMATION` types, token_seq, alerts, idempotency_keys, sync_outbox) + seed rows (Admin staff with BCrypt PIN, PS5/PS4 pricing, item categories). JPA entities for all tables, `validate` mode. Booking/queue tables themselves come later in `V003` (B15).
**Prompt:**
```
Read backend/ARCHITECTURE.md §4.1–4.2 and docs/backend-architecture.md §2 (copy DDL verbatim).
Implement TASK B02 from TASKLIST.md: Flyway baseline + seed + JPA entities.
Follow the GLOBAL RULES in TASKLIST.md.
Don't write test code.
Done when: mvn verify green AND a Testcontainers test boots the app against migrated PG (ddl validate passes) and reads the seeded Admin + pricing rows.
Show me the build output before saying you're finished.
```

### B03 — Auth: PIN login, JWT, staff CRUD
`branch: task/B03-auth-jwt-staff`
**Description:** `POST /auth/login` (`{staffId, pin, terminal}` → JWT access 15 min + rotating refresh cookie 12 h; claims `sub, role, shiftId?, terminal`), `refresh`, `logout` (revokes refresh), 5-failed-PIN → 15-min lock (423 `LOCKED_PIN`), BCrypt. Staff CRUD (Admin): create Manager/Cashier with PIN, 409 `DUPLICATE_NAME`, delete → 409 `STAFF_ON_SHIFT`. `GET/PUT /me/prefs` (`avatarColor`). Method-level role guards wired for all subsequent controllers.
**Prompt:**
```
Read backend/ARCHITECTURE.md §4.3–4.6 and docs/api-contract.md (Auth & staff).
Implement TASK B03 from TASKLIST.md: auth + JWT + staff CRUD + /me/prefs.
Follow the GLOBAL RULES in TASKLIST.md.
Don't write test code.
Done when: mvn verify green AND tests cover: happy login, wrong PIN ×5 → 423, refresh rotation, logout revocation, cashier hitting an admin endpoint → 403 envelope.
Show me the build output before saying you're finished.
```

### B04 — Idempotency filter
`branch: task/B04-idempotency`
**Description:** `Idempotency-Key` handling per `docs/api-contract.md` §1: applies to `POST /payments`, `/print-jobs`, `/sessions/*/blocks`, `/wallet/*`, `/tournaments/*/entries`, `/bookings`, `/bookings/*/cancel`, `/play-tickets`. Store key + request hash + response 48 h; identical retry replays stored response with `Idempotency-Replayed: true`; same key different body → 409 `IDEMPOTENCY_REPLAY`; missing key on a guarded route → 400.
**Prompt:**
```
Read backend/ARCHITECTURE.md §5.2 and docs/api-contract.md §1 (Idempotency).
Implement TASK B04 from TASKLIST.md: idempotency filter + idempotency_keys storage.
Follow the GLOBAL RULES in TASKLIST.md.
Don't write test code.
Done when: mvn verify green AND Testcontainers test proves: first call stored, identical retry replayed with header, mutated body under same key → 409, key expires after 48h (clock-shifted test).
Show me the build output before saying you're finished.
```

## PHASE B1 — Floor & catalog (Week 1–2)

### B05 — Stations + pricing
`branch: task/B05-stations-pricing`
**Description:** Stations CRUD (Admin writes): 409 `DUPLICATE_NAME`, delete → 409 `STATION_IN_USE`; `GET /stations` includes live session/match/arrival summary (stub the match part until B12 and the arrival part until B16). `GET/PUT /pricing` per consoleType with morning-discount fields; **new blocks only** — running sessions keep purchased prices (snapshot lives on `session_blocks.price`).
**Prompt:**
```
Read backend/ARCHITECTURE.md §4 and docs/api-contract.md (Stations & pricing).
Implement TASK B05 from TASKLIST.md: stations + pricing endpoints.
Follow the GLOBAL RULES in TASKLIST.md.
Don't write test code.
Done when: mvn verify green AND tests cover duplicate-name 409, delete-in-use 409, non-admin write 403, pricing update not affecting an existing session's block price.
Show me the build output before saying you're finished.
```

### B06 — Session state machine + blocks + clock
`branch: task/B06-sessions`
**Description:** `OPEN → RUNNING ⇄ PAUSED → LOCKED → CLOSED` with server-side block math. `POST /sessions` (409 `STATION_BUSY`; `STATION_RESERVED` hook stubbed until B12; `bookingId?|queueEntryId?` prepaid-seat params stubbed until B16), `POST /sessions/{id}/blocks` (`{delta:±1}`, idempotent, −1 below paid/consumed → 409 `BLOCKS_CONSUMED`), `POST /sessions/{id}/clock` (`START|PAUSE|RESUME`, 409 `NO_BLOCKS`), `POST /sessions/{id}/end` (409 `SESSION_HAS_BALANCE` when **net outstanding** — unpaid blocks + unsettled cart — > 0; prepaid blocks count as settled since they carry `paid_tx_id`), reads incl. `remainingSeconds` computed from `consumed_sec`/`running_since`. Morning-rate snapshot per block. One live session per station (partial unique index already in DDL).
**Prompt:**
```
Read backend/ARCHITECTURE.md §5.1, §5.9 and docs/api-contract.md (Sessions).
Implement TASK B06 from TASKLIST.md: session state machine + blocks + clock.
Follow the GLOBAL RULES in TASKLIST.md.
Don't write test code.
Done when: mvn verify green AND unit tests cover every state transition + illegal ones, block math across pause/resume, morning-window boundary pricing, net-outstanding end guard, and Testcontainers proves the one-live-session index.
Show me the build output before saying you're finished.
```

### B07 — Catalog: items, stock, carts
`branch: task/B07-catalog-carts`
**Description:** Items CRUD (Manager+ writes; stock edits audit `stock_movements`), `POST /carts` (`{type: COUNTER}` or session-bound), `PUT /carts/{id}/lines` (`{itemId, qty}`, 0 removes, 409 `OUT_OF_STOCK`, unit-price snapshot on line). Note: tournament entries and play tickets are NOT items rows — they are first-class payment lines priced from their own tables.
**Prompt:**
```
Read backend/ARCHITECTURE.md §4 and docs/api-contract.md (Cart & menu).
Implement TASK B07 from TASKLIST.md: items + stock movements + carts/lines.
Follow the GLOBAL RULES in TASKLIST.md.
Don't write test code.
Done when: mvn verify green AND tests cover stock audit rows on manual adjust, out-of-stock 409, line upsert/remove, cashier item-write 403.
Show me the build output before saying you're finished.
```

### B08 — Members, wallet, points
`branch: task/B08-members-wallet`
**Description:** Member search/create (409 `DUPLICATE_PHONE`), detail + recent visits (bookings list joins in B15), `wallet/topup` (idempotent), `wallet/redeem-points` (1pt=৳1 → wallet, 409 `INSUFFICIENT_POINTS`). Ledgers are the source of truth; `members.wallet/points` kept consistent in the same TX. Earn/redeem-at-settle rules land in B10.
**Prompt:**
```
Read backend/ARCHITECTURE.md §4 and docs/api-contract.md (Members, wallet, points).
Implement TASK B08 from TASKLIST.md: members + wallet/points ledgers.
Follow the GLOBAL RULES in TASKLIST.md.
Don't write test code.
Done when: mvn verify green AND tests cover duplicate-phone 409, topup idempotent replay, redeem below balance 409, ledger + column consistency in one TX.
Show me the build output before saying you're finished.
```

## PHASE B2 — Money path (Week 2)

### B09 — Bill computation
`branch: task/B09-bill`
**Description:** `GET /sessions/{id}/bill`: gaming = **unbilled** blocks only (snapshot prices; prepaid blocks are already paid and never re-billed), fnb from cart lines, tournament lines placeholder (wired in B12), **prepaid credit line** + `netTotal` per api-contract, `pointsRedeemable = min(points, netTotal)`. Pure computation service with exhaustive unit tests — this is the FE's bill panel contract.
**Prompt:**
```
Read backend/ARCHITECTURE.md §5.4, §5.9 and docs/api-contract.md (Billing & payments).
Implement TASK B09 from TASKLIST.md: bill computation endpoint.
Follow the GLOBAL RULES in TASKLIST.md.
Don't write test code.
Done when: mvn verify green AND unit tests cover: unbilled-blocks-only after a mid-session settle, prepaid blocks excluded from due, fnb math, points cap, empty bill.
Show me the build output before saying you're finished.
```

### B10 — Payments settle + void 🔴
`branch: task/B10-payments-settle`
**Description:** The heart. `POST /payments` in **one DB TX**: validate splits sum (409 `SPLIT_MISMATCH`), wallet floor (409 `WALLET_INSUFFICIENT`), bKash/Nagad manual TrxID required (409 `PAYMENT_REF_REQUIRED`), mark blocks paid (session continues), decrement stock + movements, points earn `floor(due/20)` / redeem as capped discount, wallet spend, immutable transaction snapshot (`public_id` like `GD-2608-047`, gaming/fnb/tournament/booking amounts), create print job **in the same TX** (render stubbed until B17 — store placeholder bytes behind the same interface), return `{transactionId, printJobId}`. `POST /payments/{id}/void` (Manager+, same-shift, full reversal: stock, ledgers, points). Refund transactions are negative (`total_due` < 0, negative splits — `amount <> 0` CHECK). Tournament entries join in B12; play tickets (`playTickets[]` → `queueTokens`) join in B16.
**Prompt:**
```
Read backend/ARCHITECTURE.md §5.2–5.4, §5.7 and docs/api-contract.md (Billing & payments).
Implement TASK B10 from TASKLIST.md: settle in one TX + void.
Follow the GLOBAL RULES in TASKLIST.md.
Don't write test code.
Done when: mvn verify green AND Testcontainers proves: one settle writes payment+blocks+stock+ledgers+print job atomically; idempotent replay returns the same transactionId/printJobId; split mismatch 409 leaves zero writes; void fully reverses.
Show me the build output before saying you're finished.
```

### B11 — Shifts + expenses + X/Z math
`branch: task/B11-shifts-expenses`
**Description:** `POST /shifts` (`{openingFloat}`, 409 `SHIFT_ALREADY_OPEN` per terminal), `GET /shifts/current/x-report` (takings method × category incl. tournament AND pre-booking lines — `tournament_amount`/`booking_amount`, zero until those modules land), `POST /shifts/current/close` (`{countedCash}` → expected/discrepancy computed, Z print job, logout semantics; discrepancy ≠ 0 writes an alert row), expenses CRUD (`?voucher=true` → P4 job), all print renders via the B10 placeholder until B17.
**Prompt:**
```
Read backend/ARCHITECTURE.md §5.7 and docs/api-contract.md (Shifts & expenses).
Implement TASK B11 from TASKLIST.md: shifts, X/Z computation, expenses.
Follow the GLOBAL RULES in TASKLIST.md.
Don't write test code.
Done when: mvn verify green AND Testcontainers covers: expected-cash math from mixed-method transactions + expenses (incl. negative refund rows), discrepancy alert row, second open shift 409, close produces Z job.
Show me the build output before saying you're finished.
```

## PHASE B3 — Tournaments (Week 2–3)

### B12 — Tournament core: schema, CRUD, entries, reserved stations 🔴
`branch: task/B12-tournament-core`
**Description:** `V002__tournaments.sql` **verbatim** from `docs/tournaments.md` §2. Tournament CRUD (Manager+, cap ∈ {4,8,16,32}), `PUT /{id}/blocks` (station blocks), reserved-station enforcement in B06's session start (409 `STATION_RESERVED`), entries via `POST /payments` `tournamentEntries[]` (register player, next seed, `qr_token`, return `entryTokens`; 409 `TOURNAMENT_FULL`/`TOURNAMENT_NOT_OPEN`) + `POST /tournaments/{id}/entries` direct, `transactions.tournament_amount` feeding B11's X/Z line, `POST /tournament-entries/{id}/check-in` (409 `ALREADY_CHECKED_IN`), cancel → status CANCELLED + block release + auto-refund transactions. Note: tournament seed tokens are per-tournament — separate from the daily queue-token counter (B15/B16).
**Prompt:**
```
Read docs/tournaments.md §1–2, §5 and backend/ARCHITECTURE.md §5.6–5.7.
Implement TASK B12 from TASKLIST.md: tournament schema + CRUD + entries + reserved stations + cancel/refund.
Follow the GLOBAL RULES in TASKLIST.md.
Don't write test code.
Done when: mvn verify green AND Testcontainers covers: entry sold through settle (seed + token returned, tournament_amount set), full 409, session on reserved station 409, cancel refunds every entry, cashier create-tournament 403.
Show me the build output before saying you're finished.
```

### B13 — Bracket engine
`branch: task/B13-bracket-engine`
**Description:** Auto-generate bracket in the **same TX** as the cap-filling sale (seeds = sale order, status → LIVE, exactly N−1 matches, `next_match_id` tree). Manual generate (Manager+): byes auto-advance, 409 `NOT_ENOUGH_PLAYERS` (<2). Winner recording: `winner_is_participant` check, propagation along `next_match_id`; final match → `winner_entry_id`, status DONE, block release. Un-started match decisions Manager+ only; started matches any role; `decided_by` recorded.
**Prompt:**
```
Read docs/tournaments.md §3 and backend/ARCHITECTURE.md §5.6.
Implement TASK B13 from TASKLIST.md: bracket generation + winner propagation.
Follow the GLOBAL RULES in TASKLIST.md.
Don't write test code.
Done when: mvn verify green AND unit tests cover all caps (4/8/16/32 → N−1 matches), auto-generate on cap fill inside the sale TX, byes on manual generate, propagation to the final + DONE + block release, cashier deciding an un-started match 403.
Show me the build output before saying you're finished.
```

### B14 — Match execution + finance
`branch: task/B14-match-execution`
**Description:** `POST /{id}/matches/{mid}/start` (any role): assign first allocated console neither hosting an unfinished match (partial unique index) nor busy with a walk-in session; 409 `NO_FREE_CONSOLE`; stamps `started_at`. `/extend` (`{minutes}`), winner endpoint role rules per B13, `remainingSeconds = (duration + extra_min)·60 − elapsed` on all reads, `?pending=true` job board with console-availability hints, `GET /{id}/finance` (Manager+ **only**, 403 otherwise; revenue / netProfit / opportunityCost / extraMargin formulas from `docs/tournaments.md` §6), `GET /tournaments/history`.
**Prompt:**
```
Read docs/tournaments.md §4, §6 and backend/ARCHITECTURE.md §5.1, §5.6.
Implement TASK B14 from TASKLIST.md: match start/extend/winner + finance + history.
Follow the GLOBAL RULES in TASKLIST.md.
Don't write test code.
Done when: mvn verify green AND tests cover: console assignment skipping walk-in-busy and match-busy stations, NO_FREE_CONSOLE 409, extend re-basing remainingSeconds, finance formulas, cashier finance 403.
Show me the build output before saying you're finished.
```

## PHASE B3.5 — Bookings & play queue (Week 3)

### B15 — Bookings core: settings, create, cancel, check-in 🔴
`branch: task/B15-bookings-core`
**Description:** `V003__bookings.sql` **verbatim** from `docs/bookings.md` §5 (`booking_settings` single row, `bookings`, `queue_entries`). `GET/PUT /booking-settings` (`{enabled, packageFee, cancelCutoffHours}`; PUT Admin only; changes apply to NEW bookings only — existing keep their snapshots). `POST /bookings` (idempotent, **pay-first in one TX**: `blocks × console-rate snapshot + package-fee snapshot`, transaction with `booking_amount`, print job P1+P7 via the B10 placeholder; overlap at create = warning only, staff overrides; 409 `PREBOOKING_DISABLED`, `SPLIT_MISMATCH`). `GET /bookings?tab=upcoming|history` (upcoming = PAID; history = ARRIVED/USED/CANCELLED). `POST /bookings/{id}/check-in` — allocates the next daily token via row-locked `token_seq` upsert, creates the `queue_entries` row (source BOOKING), P6 job, status → ARRIVED; 409 `ALREADY_CHECKED_IN`. `POST /bookings/{id}/cancel` (idempotent; only PAID and `now ≤ start_at − cutoff` snapshot; full **negative** refund transaction; 409 `CANCEL_CUTOFF_PASSED`/`ALREADY_CHECKED_IN`). Member detail (B08) gains the bookings list. Feature disabled ⇒ new bookings blocked, existing ones stay serviceable.
**Prompt:**
```
Read docs/bookings.md (all) and backend/ARCHITECTURE.md §4, §5.9–5.11.
Implement TASK B15 from TASKLIST.md: booking settings + booking lifecycle (create/check-in/cancel) + V003.
Follow the GLOBAL RULES in TASKLIST.md.
Don't write test code.
Done when: mvn verify green AND Testcontainers covers: create charges play+fee in one TX with booking_amount set; idempotent replay of create and cancel; cancel at exactly the cutoff boundary OK and inside the window 409; check-in issues sequential tokens under concurrent requests; disabled flag blocks create but existing booking still checks in; cashier PUT /booking-settings 403.
Show me the build output before saying you're finished.
```

### B16 — Play queue, prepaid seating, session integration
`branch: task/B16-play-queue-seat`
**Description:** `playTickets[]` lines in `POST /payments` (console type + blocks; sellable while all consoles busy; each issues the next daily token + `queue_entries` row source PLAY_TICKET + P6 appended to the sale's print job; returns `queueTokens`) + `POST /play-tickets` standalone alias. `GET /play-queue` (today's WAITING in token order + SEATED for history). `POST /play-queue/{id}/seat {stationId}` — **one TX**: create session, insert prepaid `session_blocks` with `paid_tx_id` = sale tx, queue entry → SEATED (+ `session_id`), booking → USED; 409 `CONSOLE_TYPE_MISMATCH`, `STATION_BUSY`. Wire B06's `POST /sessions` `bookingId?|queueEntryId?` path to the same service. `DELETE /play-queue/{id}` (Manager+: refund + remove no-show — negative transaction). Day rollover: `token_seq` restarts per date; old WAITING tokens keep working (entry id is the key). `GET /stations` arrival summary (B05 stub) now real.
**Prompt:**
```
Read docs/bookings.md §3–§4, §7 and backend/ARCHITECTURE.md §5.9–5.11.
Implement TASK B16 from TASKLIST.md: play tickets in settle + queue endpoints + seat-from-token + no-show removal.
Follow the GLOBAL RULES in TASKLIST.md.
Don't write test code.
Done when: mvn verify green AND Testcontainers covers: ticket sale while every console busy (token + queue row + booking_amount); seat creates session with prepaid blocks and session ends without further payment; PS5 ticket on PS4 console 409; booking seat flips booking to USED; no-show removal writes the refund; day-rollover test (counter restarts, old token still seatable).
Show me the build output before saying you're finished.
```

## PHASE B4 — Printing (Week 3)

### B17 — ESC/POS rendering P1–P7 🔴
`branch: task/B17-escpos-render`
**Description:** escpos-coffee renderer producing **bytes + 48-col text** at job creation, stored in `print_jobs.rendered`/`rendered_text`, replacing the B10 placeholder. Templates per `docs/design.md` §5: P1 sale ticket (incl. tender rows, loyalty line, Code 128 `GS k`), P2/P3 Z/X (incl. tournament + **pre-booking** takings lines), P4 expense voucher, P5 tournament stub (inverted band, TOKEN #NN double-height, QR `GS ( k` with `qr_token`, appended to P1 in the same job), **P6 play ticket** (sale or check-in "PLAY TICKET — PREBOOKED" heading; TOKEN #NN double-height + console type + prepaid length; "Tokens reset daily"; Code 128 = queue-entry id; appended to P1 on sale, standalone on check-in), **P7 booking confirmation** (BOOKING band, console, start time, play time, package-fee line, cancellation-policy line; part of the booking sale's P1 job). Reprint band. 80mm default, 58mm config switch. **Golden-file tests** lock bytes + text.
**Prompt:**
```
Read docs/design.md §5 (all templates + barcode/QR specs) and backend/ARCHITECTURE.md §5.5.
Implement TASK B17 from TASKLIST.md: ESC/POS render for P1–P7 + golden files.
Follow the GLOBAL RULES in TASKLIST.md.
Don't write test code.
Done when: mvn verify green AND golden-file tests exist per template (bytes + rendered_text) incl. P5 token/QR, P6 token/barcode both variants, P7 policy line and the reprint band; settle, booking create and check-in now store real renders.
Show me the build output before saying you're finished.
```

### B18 — Print queue, USB device, retry/reprint
`branch: task/B18-print-queue`
**Description:** Single-threaded worker per device claiming with `FOR UPDATE SKIP LOCKED`; `QUEUED→PRINTING→DONE|FAILED`; 3 auto-attempts 2 s backoff; DLE EOT status polling (paper-out, cover-open, offline) → specific FAILED error + alert; usb4java transport behind a `PrinterPort` interface with a fake for CI/dev. Endpoints: job status, `GET /{id}/render`, `retry` (same bytes), `reprint` (reason required → new job, band, original linked), `GET /printers`, `PUT /printers/default`, test ticket. `receipt_copies=2` emits copy in-job after the cut.
**Prompt:**
```
Read backend/ARCHITECTURE.md §5.5 and docs/api-contract.md (Print jobs) and docs/backend-architecture.md §5.
Implement TASK B18 from TASKLIST.md: print queue worker + device port + retry/reprint endpoints.
Follow the GLOBAL RULES in TASKLIST.md.
Don't write test code.
Done when: mvn verify green AND fake-port tests cover: happy print DONE, offline → FAILED after 3 attempts + alert, mid-print failure → retry re-sends identical bytes, reprint without reason 400.
Show me the build output before saying you're finished.
```

## PHASE B5 — Live data, reporting, sync, hardening (Week 3–4)

### B19 — SSE hub + alerts
`branch: task/B19-sse-alerts`
**Description:** `GET /events` SSE with `station-update` (sessions AND match timers), `queue-update`, `booking-update`, `tournament-update`, `alert`, `printer-status`, `sync-status`, emitted post-commit from session/booking/queue/tournament/printing/shift services; payloads identical to GET shapes. Alerts table + feed endpoints (low-stock, discrepancy, printer) with read flags.
**Prompt:**
```
Read backend/ARCHITECTURE.md §4.5 and docs/api-contract.md (Live updates & sync).
Implement TASK B19 from TASKLIST.md: SSE hub + alert feed.
Follow the GLOBAL RULES in TASKLIST.md.
Don't write test code.
Done when: mvn verify green AND an integration test subscribes to /events and receives station-update on block change, queue-update on ticket sale/seat, booking-update on check-in, tournament-update on extend; alert rows created on discrepancy + printer failure.
Show me the build output before saying you're finished.
```

### B20 — Reports + Overview aggregates
`branch: task/B20-reports-overview`
**Description:** Endpoints backing S2/S9 per `docs/design.md` and `docs/bookings.md` §6: KPIs (occupancy, revenue, avg ticket, net profit = takings − expenses), **pre-sold stat** (sum of PAID bookings + WAITING play tickets), 30-day + day-of-week trends, 14-day stacked trend, per-station utilisation, busiest hours, top sellers, stock watchlist, staff shift-close history, bookings per day, show-rate (`USED / (USED+CANCELLED+expired)`), package-fee income. Manager+ guard (Overview admin-only). Read-only SQL aggregates — no stored rollups.
**Prompt:**
```
Read docs/design.md (S2, S9), docs/bookings.md §6 and backend/ARCHITECTURE.md §4.3, §4.6.
Implement TASK B20 from TASKLIST.md: report + overview endpoints.
Follow the GLOBAL RULES in TASKLIST.md.
Don't write test code.
Done when: mvn verify green AND Testcontainers seeds transactions/expenses/bookings across days and asserts each aggregate incl. the tournament + booking revenue splits, pre-sold stat and show-rate; cashier 403.
Show me the build output before saying you're finished.
```

### B21 — Terminal settings + login background
`branch: task/B21-terminal-settings`
**Description:** `GET/PUT /terminal-settings` (Admin write, any role read: theme, fontScale, accent, sound, autoLockMin, receiptCopies, loginBgImageId), `POST /terminal-settings/login-bg` multipart upload (size/type validated), image serve endpoint. Per-terminal row keyed by JWT `terminal` claim.
**Prompt:**
```
Read docs/design.md §6 and docs/api-contract.md (Settings).
Implement TASK B21 from TASKLIST.md: terminal settings + login-bg upload.
Follow the GLOBAL RULES in TASKLIST.md.
Don't write test code.
Done when: mvn verify green AND tests cover: non-admin write 403, any-role read, image upload + retrieval, receiptCopies constraint.
Show me the build output before saying you're finished.
```

### B22 — Sync outbox + e2e money-path test 🔴
`branch: task/B22-sync-e2e`
**Description:** Transactional outbox rows written inside every money/inventory/tournament/booking mutation TX (retrofit check across B06–B18); 30 s pusher batching to cloud `POST /sync/push` (idempotent by op id, `SYNC_TOKEN` auth); `GET /sync/status`. Dev-profile demo seed script (incl. booking settings + a PAID booking + a WAITING play ticket). Then the **release-gate e2e** (Testcontainers): open shift → session → blocks → fnb → tournament entry sale → auto-bracket at cap → start match → winner → champion → **booking create → cancel-refund → recreate → check-in token → seat from Floor → prepaid session ends without payment → play-ticket sale while busy → seat** → split settle → print jobs → shift close with tournament + pre-booking lines + discrepancy → outbox drained to a fake cloud.
**Prompt:**
```
Read backend/ARCHITECTURE.md §5.8, §7 and docs/backend-architecture.md §9–§11.
Implement TASK B22 from TASKLIST.md: sync outbox + pusher + the full e2e money-path test.
Follow the GLOBAL RULES in TASKLIST.md.
Don't write test code.
Done when: mvn verify green AND the single e2e test drives the whole flow above; killing the fake cloud for a while loses nothing (outbox drains on reconnect).
Show me the build output before saying you're finished.
```

---

# FRONTEND (`frontend/`)

## PHASE F0 — Foundation (Week 3, parallel with B19+)

### F01 — Scaffold + design tokens
`branch: task/F01-scaffold-tokens`
**Description:** Next.js App Router + TS project in `frontend/`, Tailwind 4 with `@theme` mapping **every** `docs/design.md` §3 token (dark-default ramp, 3 accent ramps incl. dark equivalents, spacing scale, type scale, Archivo), `styles/tokens.css`, radius-0/2px-rule base, `tabular-nums` utilities, Vitest + Testing Library wiring, folder skeleton per `frontend/ARCHITECTURE.md` §3 (incl. `bookings/` + `queue/` feature folders and the S14 route).
**Prompt:**
```
Read frontend/ARCHITECTURE.md (esp. §3, §5.9) and docs/design.md §3.
Implement TASK F01 from TASKLIST.md: scaffold + full token system.
Follow the GLOBAL RULES in TASKLIST.md.
Don't write test code.
Done when: npm run build && npm test green AND a token demo page renders both themes + 3 accents with correct values (unit test asserts CSS vars).
Show me the build output before saying you're finished.
```

### F02 — UI primitives
`branch: task/F02-ui-primitives`
**Description:** Hand-rolled `components/ui`: Button (5 variants × states incl. loading, centered labels), Tag, Dialog, FieldInput, SegmentedChoice, ChipSelect, DataTable, StatTile, BarChart (plain SVG), ProgressBar, AvatarSwatch, ImagePicker, TimeStepper (−30/+30, −30 disabled at 30 min), TokenBadge (inline, stub) — variants/states/props exactly per `docs/design.md` §2, cva-driven, focus-visible outlines, 45% disabled opacity.
**Prompt:**
```
Read docs/design.md §2–3 and frontend/ARCHITECTURE.md §5.9.
Implement TASK F02 from TASKLIST.md: ui primitives per the component inventory.
Follow the GLOBAL RULES in TASKLIST.md.
Don't write test code.
Done when: npm run build && npm test green AND each primitive has a rendering test per variant/state from the design.md table.
Show me the build output before saying you're finished.
```

### F03 — API layer, types, time/money libs
`branch: task/F03-api-layer`
**Description:** `lib/api.ts` (auth header, per-intent Idempotency-Key, error-envelope → typed errors incl. the booking codes, 401 → one silent refresh → logout), OpenAPI type-gen from the backend (`/v3/api-docs`) wired into CI (drift fails build), TanStack Query provider + canonical query keys (`frontend/ARCHITECTURE.md` §4.1 incl. `['bookings', tab]`, `['booking-settings']`, `['queue']`), Zod schemas in `features/*/schemas.ts`, `lib/time.ts` (server-offset clock for all countdowns + day-rollover helper for token display), `lib/money.ts` (integer BDT format).
**Prompt:**
```
Read frontend/ARCHITECTURE.md §4–5 and docs/api-contract.md §1.
Implement TASK F03 from TASKLIST.md: api wrapper + OpenAPI type-gen + query setup + time/money libs.
Follow the GLOBAL RULES in TASKLIST.md.
Don't write test code.
Done when: npm run build && npm test green AND unit tests cover: error-code mapping (incl. CANCEL_CUTOFF_PASSED, PREBOOKING_DISABLED, CONSOLE_TYPE_MISMATCH), idempotency-key reuse on retry, 401 refresh-then-logout, server-offset countdown math, BDT formatting.
Show me the build output before saying you're finished.
```

### F04 — Login + app shell + guards
`branch: task/F04-login-shell`
**Description:** S1 login (staff picker, PIN pad, wrong-PIN inline, 5-try lockout state, admin background image under dark overlay), `middleware.ts` cookie guard, `(app)/layout.tsx` shell: sidebar from `NAV[role]` map **filtered by feature flags** (Bookings item hidden when `booking_settings.enabled` is false; LIVE badge on Tournaments), topbar (title, occupancy, clock), signed-in card, sync chip placeholder, auto-lock (PIN to unlock) per terminal settings, role redirects (S2 admin, S9 manager+).
**Prompt:**
```
Read docs/design.md (S1 + shell) and frontend/ARCHITECTURE.md §4.3.
Implement TASK F04 from TASKLIST.md: login screen + shell + role guards.
Follow the GLOBAL RULES in TASKLIST.md.
Don't write test code.
Done when: npm run build && npm test green AND tests cover NAV[role] visibility for all 3 roles, Bookings item hidden when the feature flag is off, lockout state rendering, middleware redirect, auto-lock trigger.
Show me the build output before saying you're finished.
```

### F05 — SSE + sync status
`branch: task/F05-sse-sync`
**Description:** `lib/sse.ts` subscribing to `/events` and writing into the canonical query keys (`station-update`, `queue-update`, `booking-update`, `tournament-update`, `alert`, `printer-status`, `sync-status`); 10 s polling fallback + `refetchOnReconnect`; `features/sync/use-sync-status.ts` driving the SyncChip (synced / syncing / offline since HH:MM).
**Prompt:**
```
Read frontend/ARCHITECTURE.md §4.1, §5.2 and docs/api-contract.md (Live updates & sync).
Implement TASK F05 from TASKLIST.md: SSE wiring + sync chip.
Follow the GLOBAL RULES in TASKLIST.md.
Don't write test code.
Done when: npm run build && npm test green AND tests prove SSE station-update and queue-update mutate the query cache, fallback polling activates when SSE drops, chip states render.
Show me the build output before saying you're finished.
```

## PHASE F1 — Floor & POS (Week 4)

### F06 — Floor (S3) + queue rail
`branch: task/F06-floor`
**Description:** StationCard (all 8 variants incl. reserved/booked/maintenance), CountdownClock (panel/card sizes, running/paused/overtime), SessionPanel (start no-clock, ±30-min blocks with optimistic update + `BLOCKS_CONSUMED` rollback, start/pause/resume, bill link, end blocked on **net** balance with `SESSION_HAS_BALANCE` notice, **seat-prompt variant**: "Seat #NN «name» · «len» prepaid" on free booked consoles — seating loads prepaid blocks as paid, never optimistic), **QueueRail** (WAITING entries in token order, "No one waiting" empty state, seat action disabled when no free console of the type, seat any entry — not strictly FIFO), tournament-match display on reserved stations (match countdown, players, "match over"), empty/error/permission states per design.md §1.
**Prompt:**
```
Read docs/design.md (S3, StationCard/SessionPanel/CountdownClock/QueueRail rows), docs/bookings.md §2–3 and docs/tournaments.md §4 (floor integration).
Implement TASK F06 from TASKLIST.md: Floor screen incl. queue rail + seat prompts.
Follow the GLOBAL RULES in TASKLIST.md.
Don't write test code.
Done when: npm run build && npm test green AND tests cover countdown from remainingSeconds+offset, optimistic block rollback on 409, reserved-station render, seat-prompt render + non-optimistic seat, queue-rail seat disabled with no free console of type, CONSOLE_TYPE_MISMATCH notice, end-blocked state.
Show me the build output before saying you're finished.
```

### F07 — POS (S4): menu, cart, member, tournament entries, play tickets 🔴
`branch: task/F07-pos`
**Description:** Menu grid by category incl. **Tournament** category (fee, slots left, full=disabled) and **Play-ticket** category (console type + length picker; sellable while every console is busy), station/counter toggle, BillPanel + CartLine (optimistic, qty-min removes; tournament/ticket lines), MemberSearch (attach, auto-fill, no-match notice), RedeemStepper (None/100/200/Max capped), player-name field when an entry or ticket is in cart (member auto-fill / free text / "Walk-in guest"), 1024–1279 ticket-column collapse. Settle itself lands in F08.
**Prompt:**
```
Read docs/design.md (S4 + component rows), docs/tournaments.md §5 and docs/bookings.md §3.
Implement TASK F07 from TASKLIST.md: POS screen (menu, cart, member, tournament + play-ticket categories).
Follow the GLOBAL RULES in TASKLIST.md.
Don't write test code.
Done when: npm run build && npm test green AND tests cover cart optimistic add/remove with server reconciliation, redeem cap math, tournament card disabled when full, play-ticket line while consoles busy, player-name field logic.
Show me the build output before saying you're finished.
```

### F08 — Payments + ticket preview
`branch: task/F08-payments`
**Description:** PaymentSplit (cash/bkash/nagad/wallet amounts, TrxID field required for MFS), settle via single `POST /payments` — **never optimistic**, failure keeps the bill intact with retry (409 code → message per state table), success → 80mm ticket preview from `GET /print-jobs/{id}/render` + job status polling (`use-print-job.ts`), `entryTokens` display for tournament stubs and `queueTokens` display for play-ticket stubs (TokenBadge), member points earned line.
**Prompt:**
```
Read docs/design.md (S4 settle + P1/P5/P6) and docs/api-contract.md (Billing & payments).
Implement TASK F08 from TASKLIST.md: split payment + settle + ticket preview.
Follow the GLOBAL RULES in TASKLIST.md.
Don't write test code.
Done when: npm run build && npm test green AND tests cover split-sum validation, SPLIT_MISMATCH keeps bill intact, PAYMENT_REF_REQUIRED gating, settle → preview render fetch, entry/queue token display, no optimistic write anywhere in the path.
Show me the build output before saying you're finished.
```

### F09 — Members (S6 + S6a)
`branch: task/F09-members`
**Description:** Member search/table, detail (wallet, points, visits, bookings), top-up + redeem-to-wallet dialogs (idempotent mutations), NewMemberDialog: register, optional opening top-up, "Save & seat on «station»" → starts a session and routes to Floor.
**Prompt:**
```
Read docs/design.md (S6, S6a).
Implement TASK F09 from TASKLIST.md: members screen + new-member dialog.
Follow the GLOBAL RULES in TASKLIST.md.
Don't write test code.
Done when: npm run build && npm test green AND tests cover search debounce, DUPLICATE_PHONE inline error, redeem 409, save-and-seat flow invalidating sessions+stations.
Show me the build output before saying you're finished.
```

## PHASE F2 — Bookings, tournaments & operations (Week 4–5)

### F10 — Bookings (S14)
`branch: task/F10-bookings`
**Description:** **Upcoming / History tabs** over BookingTable (upcoming = PAID with count; history = ARRIVED/USED/CANCELLED; row click selects with accent outline); rate card. Right rail: idle = one "New booking" button + hint; selected = **BookingDetail** (customer, console, starts, play time, paid, status; "Check in & print token" → TokenBadge confirmation + thermal stub preview; "Cancel & refund" or cutoff lock note per `CANCEL_CUTOFF_PASSED`); "New booking" = **BookingForm** pay-first (console chips, member attach via MemberSearch or free name/phone, start time — date-time picker with same-console overlap **warning only**, TimeStepper, live bill box = play time × console rate + package fee client-computed with the server total authoritative at confirm, payment method, "Take ৳N & confirm booking"). Mutations never optimistic; confirm failure keeps the form. Empty/error/disabled states per design.md §1; rail overlays at 1024–1279.
**Prompt:**
```
Read docs/bookings.md (all) and docs/design.md (S14 row + state table + BookingTable/BookingDetail/BookingForm/TimeStepper/TokenBadge rows).
Implement TASK F10 from TASKLIST.md: bookings screen (tabs, table, detail rail, pay-first form).
Follow the GLOBAL RULES in TASKLIST.md.
Don't write test code.
Done when: npm run build && npm test green AND tests cover tab filtering, bill-box math (blocks × rate + fee) + server-total drift notice, cutoff lock note rendering, check-in token confirmation flow, cancel-refund never optimistic, empty states for both tabs, PREBOOKING_DISABLED notice.
Show me the build output before saying you're finished.
```

### F11 — Tournaments (S12) 🔴
`branch: task/F11-tournaments`
**Description:** Tabs Live&upcoming/History; left-rail tournament cards; pre-bracket registered-player list; BracketView 4/8/16/32 (per-match tags `console · mm:ss`, Winner ✓ chips, red W, dimmed losers), LiveMatchTile (TIME UP accent state), MatchBoard (start disabled on no-free-console/busy hints, +5 min re-base), ChampionBanner; Manager rail (arrange form with 2ⁿ chips, station-block chips, cancel, FinancePanel) mounted Manager+ only; Cashier rail (guidance + POS deep-link). Winner recording never optimistic; failure banner per state table.
**Prompt:**
```
Read docs/tournaments.md §8 (+§4) and docs/design.md (S12 component rows).
Implement TASK F11 from TASKLIST.md: tournaments screen incl. bracket, match board, role rails.
Follow the GLOBAL RULES in TASKLIST.md.
Don't write test code.
Done when: npm run build && npm test green AND tests cover bracket rendering for all caps (N−1 boxes, propagation display), extend re-basing the countdown, manager-rail absent for cashier, finance query not mounted for cashier.
Show me the build output before saying you're finished.
```

### F12 — Shift close (S7) + Expenses (S8)
`branch: task/F12-shift-expenses`
**Description:** S7: X-report by method, tournament-entries AND pre-booking reconciliation strips, editable drawer count with **live discrepancy**, petty-cash list, Z print → closes shift → login. S8: expense table + record form (category chips), voucher print option.
**Prompt:**
```
Read docs/design.md (S7, S8) and docs/bookings.md §6.
Implement TASK F12 from TASKLIST.md: shift close + expenses screens.
Follow the GLOBAL RULES in TASKLIST.md.
Don't write test code.
Done when: npm run build && npm test green AND tests cover live discrepancy math, pre-booking strip rendering, close flow → logout redirect, expense form validation + voucher job trigger; shift close never optimistic.
Show me the build output before saying you're finished.
```

### F13 — Inventory (S5) + Setup (S10)
`branch: task/F13-inventory-setup`
**Description:** S5 read-only stock table + low-stock rail + equipment register. S10 role-sectioned: Admin sees stations/pricing/staff (add with PIN, remove → `STAFF_ON_SHIFT` notice) + menu + **pre-booking controls** (enable/disable, package fee ৳, cancellation cutoff hours — Admin only; disabling hides the Bookings nav item); Manager sees menu & stock only; printing card (devices, default, 80/58 toggle, test ticket).
**Prompt:**
```
Read docs/design.md (S5, S10), docs/bookings.md §1 and frontend/ARCHITECTURE.md §4.3.
Implement TASK F13 from TASKLIST.md: inventory + setup screens.
Follow the GLOBAL RULES in TASKLIST.md.
Don't write test code.
Done when: npm run build && npm test green AND tests cover role-sectioning (manager vs admin vs cashier-hidden), booking controls admin-only + nav-hide on disable, staff-on-shift 409 notice, printer default persistence.
Show me the build output before saying you're finished.
```

### F14 — Overview (S2) + Reports (S9)
`branch: task/F14-overview-reports`
**Description:** S2: stat tiles incl. **pre-sold bookings stat**, horizontally scrolling live-station cards (click → Floor), 30-day + day-of-week trends (plain SVG bars), stock watchlist, staff & shift closes, collapsible AlertsRail (bell + unread badge); stale-data banner with last-sync. S9: KPIs, 14-day stacked trend, per-station utilisation, busiest hours, top sellers, bookings per day + show-rate + package-fee income; "Not enough data yet" per chart.
**Prompt:**
```
Read docs/design.md (S2, S9) and docs/bookings.md §6.
Implement TASK F14 from TASKLIST.md: overview + reports screens.
Follow the GLOBAL RULES in TASKLIST.md.
Don't write test code.
Done when: npm run build && npm test green AND tests cover admin-only redirect for S2, pre-sold stat rendering, empty-chart states, alerts rail unread badge from the alerts query.
Show me the build output before saying you're finished.
```

### F15 — Settings (S13) + Print preview (S11)
`branch: task/F15-settings-print`
**Description:** S13 per design.md §6: theme/text-size/accent applied **instantly** from local state + persisted via `PUT /terminal-settings`; inline pre-paint script sets `data-theme` (no flash); login-bg ImagePicker; sound, auto-lock, receipt copies; avatar color via `/me/prefs`. S11: exact character-grid render from the server (receipt, Z/X, tournament stub, play-ticket stub), job states (rendering/ready/queued/failed/retry), reprint with required reason picker.
**Prompt:**
```
Read docs/design.md §6 + §5 (S11 states) and frontend/ARCHITECTURE.md §5.5–5.6.
Implement TASK F15 from TASKLIST.md: settings + print preview screens.
Follow the GLOBAL RULES in TASKLIST.md.
Don't write test code.
Done when: npm run build && npm test green AND tests cover instant theme apply + persistence, no-flash inline script presence, reprint blocked without reason, failed-job retry flow.
Show me the build output before saying you're finished.
```

## PHASE F3 — Hardening (Week 5–6)

### F16 — State-coverage + responsive pass
`branch: task/F16-state-coverage`
**Description:** Sweep every screen (S1–S14) against the design.md §1 state table (default/loading skeleton matching layout/empty/error/permission-denied — incl. S14's two empty tabs and the feature-disabled state) and §4 breakpoints (1280 collapse incl. Bookings rail overlay, 768 icon sidebar + drawer, <768 notice). Fix gaps; add the state-table assertion tests the architecture demands. No new features.
**Prompt:**
```
Read docs/design.md §1 (state coverage table) + §4 and frontend/ARCHITECTURE.md §5.10.
Implement TASK F16 from TASKLIST.md: state-coverage + responsive audit across all screens.
Follow the GLOBAL RULES in TASKLIST.md.
Don't write test code.
Done when: npm run build && npm test green AND every screen has state-table tests passing; list each gap you fixed.
Show me the build output before saying you're finished.
```

### F17 — Playwright e2e suite 🔴
`branch: task/F17-playwright-e2e`
**Description:** Playwright against the seeded dev backend (B22 seed): session → blocks → bill → split settle → ticket · counter sale · member redeem · **booking create → cancel-refund at/inside cutoff → recreate → check-in token → seat from Floor → prepaid clock** · **play-ticket sale while consoles busy → queue rail → seat → auto-loaded timer → add time** · tournament entry sale → auto-bracket at cap → start match → winner → champion · shift close with discrepancy incl. tournament + pre-booking lines · print retry after simulated printer-offline. Wire OpenAPI type-drift check + both test suites into CI. **Release gate.**
**Prompt:**
```
Read frontend/ARCHITECTURE.md §6 and TASKLIST.md B22 (seeded backend).
Implement TASK F17 from TASKLIST.md: Playwright money-path suite + CI wiring.
Follow the GLOBAL RULES in TASKLIST.md.
Don't write test code.
Done when: npm run build && npm test green AND all listed Playwright scenarios pass against the seeded backend; CI fails on OpenAPI type drift.
Show me the build output before saying you're finished.
```

---

## Schedule (1 engineer, ~5–6 weeks)

| Week | Tasks | Theme |
|---|---|---|
| 1 | B01–B06 | Backend foundation + auth + sessions |
| 2 | B07–B12 | Catalog, members, **money path**, tournament core |
| 3 | B13–B16 · F01–F03 | Bracket + matches, **bookings & play queue** · FE foundation starts |
| 4 | B17–B22 · F04–F08 | Printing, SSE, reports, sync · shell, floor, POS, payments |
| 5 | F09–F14 | Members, **bookings UI**, tournaments UI, ops screens |
| 6 | F15–F17 | Settings, hardening, e2e |

🔴 heavy tasks (B10, B12, B15, B17, B22, F07, F11, F17): budget 1.5–2 sessions; the rest ~1.

## Dependency notes
- B01–B04 block all backend tasks; B10 (settle) blocks B11/B12/B15/B16; B12 blocks B13/B14; B15 (booking core) blocks B16 (queue/seat); B15+B16 block B17's P6/P7 renders; B17 blocks B18's real bytes (B18's queue can use the fake port).
- F01–F05 block all screens; F03's type-gen needs the backend running (any state ≥ B04 — regenerate as endpoints land).
- F07/F08 need B10 (play-ticket lines need B16); F06's queue rail + seat prompts need B16; F10 needs B15/B16; F11 needs B13/B14; F15 needs B21; F17 needs **everything** + B22's seed.
- Print render placeholder (B10/B15) is intentional — do not build P1–P7 early; B17 swaps it behind the same interface.
- Cut line if the timeline runs tight (from `docs/backend-architecture.md` §12): report charts (keep summary numbers) → alerts feed (keep discrepancy alert) → cloud sync (nightly dump only) → tournament finance panel → booking overlap warnings (keep create + cancel + check-in). Cannot cut: money path, idempotency, printing, bracket integrity, booking refund correctness, shift close.
- Open flags — stop-and-ask, never guess: printer model (80mm assumed), bKash/Nagad credentials (manual TrxID is MVP), morning-discount hours.
