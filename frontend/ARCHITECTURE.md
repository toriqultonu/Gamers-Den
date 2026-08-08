# Gamer's Den Frontend — Execution Architecture

Companion to `docs/frontend-architecture.md` (design rationale), `docs/design.md` (**the authority** for screens, states, components, tokens, print templates), `docs/tournaments.md` (S12 behavior) and `docs/api-contract.md` (**the authority** for data shapes). The prototype `Gamers Den.dc.html` is the visual reference.
This file is the **build-time reference**: canonical names, folder map, and gates every task in `TASKLIST.md` must obey. Edit here first; task texts reference this file.

---

## 1. Context

Next.js App Router + TypeScript app served to a fixed desktop counter terminal (min 1366×768, designed 1440×900) by the Spring Boot backend on the same cafe PC; the owner reaches the same app via cloud. Dark theme is the **default** (dim venue). One engineer, one month — hand-rolled components, one store, no visual library.

## 2. Stack (fixed — do not re-litigate)

| Concern | Choice |
|---|---|
| Framework | Next.js App Router + TypeScript |
| Styling | Tailwind CSS 4 — `@theme` maps 1:1 to design.md §3 tokens (dark-default ramp + 3 accent ramps) |
| Components | Hand-rolled primitives + `class-variance-authority`; **radius 0, 2px rules** everywhere |
| Server state | TanStack Query v5; SSE feeds `setQueryData`; `staleTime` 10 s + polling fallback |
| Client state | Zustand, **one store** |
| Forms | react-hook-form + Zod (schemas shared with DTO parsing via `z.infer`) |
| HTTP | native `fetch` wrapper `lib/api.ts` (~80 lines) |
| Charts | plain SVG/divs (bars + progress rows only) |
| Dates | date-fns + `@date-fns/tz`, `Asia/Dhaka` |
| Types | generated from backend OpenAPI (springdoc) in CI — drift fails the build |
| Tests | Vitest + Testing Library (unit) · Playwright vs seeded backend (integration) |

## 3. Folder map (canonical)

```
src/
  app/
    (auth)/login/page.tsx            # S1
    (app)/layout.tsx                 # shell: sidebar NAV[role], topbar, sync chip
    (app)/overview/page.tsx          # S2  admin guard
    (app)/floor/page.tsx             # S3
    (app)/pos/page.tsx               # S4
    (app)/inventory/page.tsx         # S5
    (app)/members/page.tsx           # S6 (+ NewMemberDialog = S6a)
    (app)/tournaments/page.tsx       # S12
    (app)/shift/page.tsx             # S7
    (app)/expenses/page.tsx          # S8
    (app)/reports/page.tsx           # S9  manager+ guard
    (app)/setup/page.tsx             # S10 role-sectioned
    (app)/settings/page.tsx          # S13
    (app)/print/[jobId]/page.tsx     # S11
  components/
    ui/                              # Button, Tag, Dialog, FieldInput, SegmentedChoice,
                                     # ChipSelect, DataTable, StatTile, BarChart, ProgressBar…
    domain/                          # StationCard, SessionPanel, CountdownClock, MenuItemCard,
                                     # BillPanel, CartLine, MemberSearch, RedeemStepper,
                                     # PaymentSplit, ReceiptPreview, BracketView, MatchBox,
                                     # LiveMatchTile, MatchBoard, ChampionBanner, FinancePanel,
                                     # AlertsRail, SyncChip, TopBar, AvatarSwatch, ImagePicker
  features/
    sessions/{queries,mutations}.ts
    pos/bill-store.ts
    payments/schemas.ts
    tournaments/{queries,mutations}.ts
    printing/use-print-job.ts
    settings/use-terminal-settings.ts
    sync/use-sync-status.ts
  lib/
    api.ts   sse.ts   money.ts   time.ts    # time.ts = server-offset clock for ALL countdowns
  styles/tokens.css
middleware.ts
```

Component variants/states/props are enumerated in `docs/design.md` §2 — build to that table, not from memory.

## 4. Canonical names

### 4.1 Query keys

Namespaced arrays: `['sessions']`, `['sessions', id]`, `['sessions', id, 'bill']`, `['stations']`, `['items']`, `['members', q]`, `['members', id]`, `['tournaments']`, `['tournaments', id]`, `['tournaments', id, 'finance']`, `['shift', 'current']`, `['expenses']`, `['reports', range]`, `['print-jobs', id]`, `['printers']`, `['terminal-settings']`, `['sync']`, `['alerts']`. SSE handlers write into these exact keys.

### 4.2 Zustand store (the only true client state)

`selectedStationId` · `posMode` (`'station' | 'counter'`) · bill-draft flags · `alertsRailOpen` · `selectedTournamentId`. Everything else is server state — if it can come from a query, it must.

### 4.3 Routes & guards

`middleware.ts` checks the auth cookie for `(app)` routes. Role guards: S2 admin, S9 manager+, S10 role-sectioned — server-side redirect AND the API 403s regardless (UI hiding is cosmetic; render 403 as an access notice, per design.md §1 state table). Nav renders from one `NAV[role]` map. S12 renders for all roles; manager rail + finance query mount only for Manager+.

### 4.4 Error codes → UI

`lib/api.ts` parses the error envelope once into a typed error. Domain codes (canonical list in `backend/ARCHITECTURE.md` §4.4) map to the design.md §1 state tables — e.g. `SESSION_HAS_BALANCE` blocks End session, `STATION_RESERVED` explains the tournament block, `SPLIT_MISMATCH` keeps the bill intact with retry. 401 → one silent refresh → hard logout. **An error never destroys entered data.**

## 5. Invariants the code must keep true

1. **Server components by default;** anything with a timer, subscription, form, or optimistic mutation is a client component (Floor, POS, Tournaments, SessionPanel, clocks, forms, alerts rail, Settings).
2. **All countdowns** (session blocks AND tournament matches) tick from `remainingSeconds` + the server-time offset in `lib/time.ts` — never local wall-clock. A `tournament-update` (+5 min extend) re-bases the tick.
3. **Optimistic:** cart lines, block ±. **Never optimistic:** payments, shift close, print jobs, winner recording.
4. **Idempotency-Key** (uuid) attached by `lib/api.ts` to every mutating money/print call; key is per user intent, reused on retry.
5. **Theme before first paint:** inline script sets `html[data-theme]` + accent/font CSS vars from cached terminal settings — no flash. Persist via `PUT /terminal-settings`.
6. **Print preview shows the server's stored render** (`GET /print-jobs/{id}/render`) — no client-side receipt drawing, ever.
7. **Carts are server-side from the first line-add** — a mid-bill refresh loses nothing.
8. **Offline (cloud down) changes nothing:** browser talks to the local backend; sync chip shows "offline since HH:MM".
9. **Design discipline:** radius 0, 2px rules, `tabular-nums` on bills/clocks/tables, body copy never in raw accent (use `accent-strong`), Lucide icons only, every screen usable at 1366×768 with the design.md §4 breakpoint behavior.
10. **Every screen ships all five states** — default, loading skeleton matching layout, empty, error, permission-denied — per the design.md §1 coverage table. A screen without them is not done.

## 6. Testing gates (every task)

- `npm run build` + `npm test` (Vitest) green = task done; typecheck failures included.
- Unit: money/time libs, Zod schemas, bill math, bracket rendering (N−1 matches, winner propagation), NAV permission map, theme application.
- Integration (Playwright, seeded backend — F16): session → blocks → bill → split settle → ticket · counter sale · member redeem · tournament entry → auto-bracket → start match → winner → champion · shift close with discrepancy · print retry after printer-offline.
- No snapshot tests; assert the state tables per screen instead.

## 7. Open flags (do not guess — ask)

Morning-discount hours display (confirm 10:00–14:00) · phase-2 verified MFS UI (build manual-TrxID only; verified flow behind config later) · owner phone layout (<768px shows "use a larger screen" notice — deliberate).
