# Gamer's Den — API Contract

Authority document: where frontend/backend specs conflict, this file wins. Tournament endpoints are summarized here and detailed in `tournaments.md`; booking/queue behavior detailed in `bookings.md`.
Base URL: `/api/v1`. JSON, UTF-8. Times ISO-8601 with offset (`+06:00`). Money in integer BDT.

---

## 1. Conventions

### Error format (all non-2xx)

```json
{ "error": { "code": "SESSION_HAS_BALANCE", "message": "Human-readable", "details": { "field": "..." }, "traceId": "..." } }
```

Standard codes: `VALIDATION_FAILED` 400, `UNAUTHORIZED` 401, `FORBIDDEN` 403, `NOT_FOUND` 404, `CONFLICT` 409, `IDEMPOTENCY_REPLAY` 409, `LOCKED_PIN` 423, `RATE_LIMITED` 429, `PRINTER_UNAVAILABLE` 503, `SYNC_UNAVAILABLE` 503. Domain codes per endpoint.

### Auth

Staff id + 4-digit PIN → JWT access (15 min) + rotating refresh cookie (12 h). 5 failed PINs → 15-min lock. Claims: `sub`, `role`, `shiftId?`, `terminal`. Logout revokes refresh.

Permission matrix (API-enforced; UI hiding cosmetic):

| Capability | Admin | Manager | Cashier |
|---|---|---|---|
| Sessions, POS, payments, prints | ✓ | ✓ | ✓ |
| Members create/top-up/redeem | ✓ | ✓ | ✓ |
| Expenses, shift open/close | ✓ | ✓ | ✓ (own shift) |
| Bookings: create (take payment), check-in + token print, seat, cancel-with-refund (outside cutoff) | ✓ | ✓ | ✓ |
| Play tickets: sell, seat from queue, add time | ✓ | ✓ | ✓ |
| Pre-booking settings (enable/disable, package fee, cutoff) | ✓ | ✗ | ✗ |
| Tournament: sell entry, start match, +time, record winner of a *started* match, view bracket/history | ✓ | ✓ | ✓ |
| Tournament: create/edit/cancel, station blocks, generate bracket, decide un-started matches, finance | ✓ | ✓ | ✗ |
| Menu & stock CRUD | ✓ | ✓ | ✗ |
| Reports, Overview | ✓ | ✓ (reports only) | ✗ |
| Stations CRUD, pricing, staff CRUD, terminal settings write | ✓ | ✗ | ✗ |
| Void/reprint others' transactions | ✓ | ✓ | ✗ |
| Own profile prefs (avatar color) | ✓ | ✓ | ✓ |

### Pagination / filtering / sorting

`?page=0&size=50&sort=createdAt,desc&filter[field]=value` → `{ "content": [...], "page", "size", "totalElements", "totalPages" }`.

### Idempotency

All mutating money/print endpoints (`POST /payments`, `/print-jobs`, `/sessions/*/blocks`, `/wallet/*`, `/tournaments/*/entries`, `/bookings`, `/bookings/*/cancel`, `/play-tickets`) require `Idempotency-Key: <uuid>`. Stored 48 h with the first response; identical retry returns it with `Idempotency-Replayed: true`; different body under same key → 409. **A retried settle, booking, or print can never double-charge, double-register, or double-print.**

---

## 2. Endpoints

### Auth & staff

| Method | Path | Notes |
|---|---|---|
| POST | `/auth/login` | `{staffId, pin, terminal}` → tokens + staff. 401, 423 |
| POST | `/auth/refresh` · `/auth/logout` | |
| GET/POST | `/staff` | Admin. Create: `{name, role: MANAGER\|CASHIER, pin}`. 409 `DUPLICATE_NAME` |
| PATCH/DELETE | `/staff/{id}` | Admin. Delete: 409 `STAFF_ON_SHIFT` |

### Stations & pricing (Admin writes)

| Method | Path | Notes |
|---|---|---|
| GET | `/stations` | + live session/match/arrival summary |
| POST | `/stations` | `{name, consoleType: PS5\|PS4}` 409 `DUPLICATE_NAME` |
| PATCH/DELETE | `/stations/{id}` | Delete: 409 `STATION_IN_USE` |
| GET/PUT | `/pricing`, `/pricing/{consoleType}` | New blocks only; running sessions keep purchased prices |

### Sessions (floor)

State machine: `OPEN` (no time) → `RUNNING` ⇄ `PAUSED` → `LOCKED` → `CLOSED`. Server-side block math; clients render from `remainingSeconds` + server timestamp.

| Method | Path | Notes |
|---|---|---|
| POST | `/sessions` | `{stationId, memberId?, bookingId?\|queueEntryId?}` → OPEN. With `bookingId`/`queueEntryId`: loads the prepaid blocks as paid and consumes the token. 409 `STATION_BUSY`, `STATION_RESERVED`, `CONSOLE_TYPE_MISMATCH` |
| POST | `/sessions/{id}/blocks` | Idempotent `{delta: ±1}`. −1 below paid/consumed → 409 `BLOCKS_CONSUMED` |
| POST | `/sessions/{id}/clock` | `{action: START\|PAUSE\|RESUME}`. 409 `NO_BLOCKS` |
| POST | `/sessions/{id}/end` | 409 `SESSION_HAS_BALANCE` if net unsettled (charges − prepaid) > 0 |
| GET | `/sessions/{id}`, `/sessions?active=true` | |

### Pre-bookings (detail: bookings.md)

| Method | Path | Notes |
|---|---|---|
| GET | `/booking-settings` | `{enabled, packageFee, cancelCutoffHours}` (any role) |
| PUT | `/booking-settings` | Admin only |
| GET | `/bookings?tab=upcoming\|history` | upcoming = PAID; history = ARRIVED/USED/CANCELLED |
| POST | `/bookings` | Idempotent. `{stationId, memberId?, name, phone?, startAt, blocks, method, paymentRef?}` — charges play time (blocks × console rate) + package fee in one transaction; returns booking + `printJobId` (P7). 409 `PREBOOKING_DISABLED`, `SPLIT_MISMATCH` |
| POST | `/bookings/{id}/check-in` | Assigns next daily queue token, prints P6 stub → `{token, printJobId}`. 409 `ALREADY_CHECKED_IN` |
| POST | `/bookings/{id}/cancel` | Idempotent. Full refund transaction (negative, same shift rules). 409 `CANCEL_CUTOFF_PASSED`, `ALREADY_CHECKED_IN` |

### Play queue (walk-up prepaid tokens)

| Method | Path | Notes |
|---|---|---|
| GET | `/play-queue` | today's WAITING entries in token order (+ SEATED for history) |
| POST | `/play-tickets` | Sold via `POST /payments` `playTickets[]` (below); this alias exists for standalone sale. Returns `{token, printJobId}` |
| POST | `/play-queue/{id}/seat` | `{stationId}` → creates the session with prepaid blocks. 409 `CONSOLE_TYPE_MISMATCH`, `STATION_BUSY` |
| DELETE | `/play-queue/{id}` | Manager+: refund & remove a no-show (refund transaction) |

Token counter resets at day rollover (venue timezone), shared across bookings and play tickets.

### Cart & menu

| Method | Path | Notes |
|---|---|---|
| GET/POST/PATCH/DELETE | `/items…` | Manager+ writes; stock edits audit `stock_movements` |
| POST | `/carts` | `{type: COUNTER}` for counter sales |
| PUT | `/carts/{id}/lines` | `{itemId, qty}` (0 removes). 409 `OUT_OF_STOCK` |

### Members, wallet, points

| Method | Path | Notes |
|---|---|---|
| GET | `/members?q=` | name/phone search (also used by the booking form's member attach) |
| POST | `/members` | 409 `DUPLICATE_PHONE` |
| GET | `/members/{id}` | + recent visits and bookings |
| POST | `/members/{id}/wallet/topup` | Idempotent. `{amount, method, paymentRef?}` |
| POST | `/members/{id}/wallet/redeem-points` | Idempotent. 1pt=৳1 → wallet. 409 `INSUFFICIENT_POINTS` |

Earn on settle: floor(due/20) to the attached member; redemption at settle is a bill discount capped at min(points, total).

### Billing & payments

| Method | Path | Notes |
|---|---|---|
| GET | `/sessions/{id}/bill` | gaming (unbilled blocks only), fnb, tournament lines, prepaid credit, pointsRedeemable, netTotal |
| POST | `/payments` | Idempotent. `{target:{sessionId?\|cartId?}, redeemPoints?, tournamentEntries?:[{tournamentId, playerName?}], playTickets?:[{consoleType, blocks, playerName?}], splits:[{method, amount, paymentRef?}]}` → `{transactionId, printJobId, entryTokens?, queueTokens?}`. Marks blocks paid (session continues), decrements stock, ledgers, registers entries/queue tokens, auto-creates print job(s). 409 `SPLIT_MISMATCH`, `WALLET_INSUFFICIENT`, `PAYMENT_REF_REQUIRED`, `TOURNAMENT_FULL` |
| POST | `/payments/{id}/void` | Manager+, `{reason}`, same-shift; full reversal |
| GET | `/transactions` | filters: shift, method, station, dates |

**bKash / Nagad:** MVP = manual TrxID (`verifyState: MANUAL`). Phase-2 behind config: `POST /payments/mfs/initiate`, HMAC-verified webhooks flip `VERIFIED`. Merchant credentials unconfirmed — flag.

### Shifts & expenses

| Method | Path | Notes |
|---|---|---|
| POST | `/shifts` | `{openingFloat}`. 409 `SHIFT_ALREADY_OPEN` per terminal |
| GET | `/shifts/current/x-report` | takings incl. tournament and pre-booking lines; `?print=true` → P3 job |
| POST | `/shifts/current/close` | `{countedCash, handoverNote?}` → Z + P2 job + logout; discrepancy ≠ 0 writes alert |
| GET | `/shifts` · POST/GET `/expenses` | expense `?voucher=true` → P4 job |

### Tournaments (summary — detail in tournaments.md)

`GET /tournaments`, `GET /tournaments/{id}` (+bracket incl. per-match `startedAt`, `extraMinutes`, `remainingSeconds`), `POST /tournaments` (Manager+, cap ∈ {4,8,16,32}), `PATCH`, `PUT /{id}/blocks`, `POST /{id}/cancel` (auto-refunds), `POST /{id}/entries`, `POST /{id}/bracket` (Manager+), `POST /{id}/matches/{mid}/start`, `POST /{id}/matches/{mid}/extend`, `POST /{id}/matches/{mid}/winner`, `GET /tournaments/{id}/finance` (Manager+ only), `GET /tournaments/history`, `POST /tournament-entries/{id}/check-in`.

### Settings

| Method | Path | Notes |
|---|---|---|
| GET/PUT | `/terminal-settings` | Admin write. `{theme, fontScale, accent, loginBgImageId?, sound, autoLockMin, receiptCopies}`; any role reads |
| POST | `/terminal-settings/login-bg` | Admin. multipart image upload → id |
| GET/PUT | `/me/prefs` | Any role. `{avatarColor}` |

### Print jobs

| Method | Path | Notes |
|---|---|---|
| POST | `/print-jobs` | Idempotent. `{type: RECEIPT\|Z_REPORT\|X_REPORT\|EXPENSE_VOUCHER\|TOURNAMENT_STUB\|PLAY_TICKET\|BOOKING_CONFIRMATION, refId}` |
| GET | `/print-jobs/{id}` | status QUEUED\|PRINTING\|DONE\|FAILED, attempts, device, operator, isReprint, reprintReason?, originalJobId? |
| GET | `/print-jobs/{id}/render` | stored 48-col text for S11 |
| POST | `/print-jobs/{id}/reprint` | `{reason: LOST\|DAMAGED\|CUSTOMER_COPY\|DISPUTE}` required → new job, band printed, original linked |
| POST | `/print-jobs/{id}/retry` | re-queue FAILED, same bytes |
| GET | `/printers` · PUT `/printers/default` | live status: ONLINE, OFFLINE, OUT_OF_PAPER, COVER_OPEN |

### Live updates & sync

`GET /events` — SSE: `station-update` (sessions AND tournament match timers), `queue-update`, `booking-update`, `tournament-update`, `alert`, `printer-status`, `sync-status`. Polling fallback 10 s.
`GET /sync/status` → `{state, lastSyncedAt, pendingOps}`; cloud-side `POST /sync/push` (ordered, idempotent by op id). One-way venue → cloud in MVP.

---

## 3. Status codes

201 create · 200 read/action · 204 delete/logout · 400 validation · 401 auth · 403 role · 404 · 409 conflict/idempotency · 422 provider verification · 423 PIN lock · 429 · 500 with traceId · 503 printer/sync.
