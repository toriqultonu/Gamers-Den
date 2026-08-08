# Gamer's Den — Frontend Architecture

Stack (fixed): Next.js App Router + TypeScript. Runs on the cafe PC in a browser; same app served to the owner via cloud. One engineer, one month — every choice minimizes moving parts. `api-contract.md` is the authority for data shapes.

---

## 1. Supporting libraries

| Concern | Choice | Rationale | Rejected alternative |
|---|---|---|---|
| Styling | Tailwind CSS 4 | Token-driven (`@theme` maps 1:1 to design.md tokens incl. the dark-default ramp and the three accent ramps), zero runtime | CSS Modules — no token enforcement; styled-components — runtime, poor RSC fit |
| Component layer | Hand-rolled primitives + `class-variance-authority` | ~30 small components; a library would be fought on radius-0, 2px rules, density | shadcn/ui — restyling cost exceeds writing Button/Dialog/Table directly |
| Server state | TanStack Query v5 | Caching, retries, optimistic updates, `refetchOnReconnect`; SSE feeds `setQueryData` | RTK Query — drags Redux; SWR — weaker mutations |
| Client state | Zustand (one store) | Only true client state: selected station, POS mode, bill draft flags, alerts-rail open, selected tournament | Redux Toolkit — ceremony |
| Forms + validation | react-hook-form + Zod | Schemas shared with API DTO parsing (`z.infer`); many small forms (member, expense, staff, item, tournament) | Formik — heavier |
| HTTP | Native `fetch` wrapper (`lib/api.ts`) | Auth header, Idempotency-Key, error envelope, 401→refresh retry (~80 lines) | axios |
| Charts | Plain SVG/divs | Bars and progress rows only | recharts — bundle + off-style |
| Dates | date-fns + `@date-fns/tz` | `Asia/Dhaka` | dayjs |
| Barcode/QR preview | none | S11 shows the server's stored render — no client drawing, no divergence | jsbarcode / qrcode |

## 2. Folder structure

```
src/
  app/
    (auth)/login/page.tsx            # S1 (client; bg image from terminal settings)
    (app)/layout.tsx                 # shell: sidebar (role-aware NAV map), topbar, sync chip
    (app)/overview/page.tsx          # S2 (admin guard)
    (app)/floor/page.tsx             # S3
    (app)/pos/page.tsx               # S4
    (app)/inventory/page.tsx         # S5
    (app)/members/page.tsx           # S6 (+ NewMemberDialog)
    (app)/tournaments/page.tsx       # S12
    (app)/shift/page.tsx             # S7
    (app)/expenses/page.tsx          # S8
    (app)/reports/page.tsx           # S9 (manager+ guard)
    (app)/setup/page.tsx             # S10 (role-sectioned)
    (app)/settings/page.tsx          # S13
    (app)/print/[jobId]/page.tsx     # S11
  components/
    ui/button.tsx                    # primitives per design.md §2
    domain/station-card.tsx          # StationCard, SessionPanel, BillPanel, BracketView, MatchBoard, ...
  features/
    sessions/{queries,mutations}.ts
    pos/bill-store.ts
    payments/schemas.ts
    tournaments/{queries,mutations}.ts   # bracket, match start/extend/winner, finance (manager-gated)
    printing/use-print-job.ts
    settings/use-terminal-settings.ts    # theme/font/accent applied as html[data-theme] + CSS vars
    sync/use-sync-status.ts
  lib/
    api.ts · sse.ts · money.ts · time.ts # server-offset clock for ALL countdowns (sessions + matches)
  styles/tokens.css
middleware.ts
```

## 3. Server vs Client Components

Pages and layout shells are Server Components; anything with a timer, subscription, form, or optimistic mutation is a Client Component. Floor, POS, Tournaments (bracket timers), SessionPanel, clocks, forms, alerts rail, Settings = client. Menu/stations/terminal-settings prefetched server-side into the hydration boundary.

## 4. Data, caching, errors

- Query keys namespaced (`['sessions', id]`, `['tournaments', id]`…). Live data hydrates from SSE; `staleTime` 10 s with polling fallback.
- All countdowns (session blocks, tournament matches) tick client-side from `remainingSeconds` + a server-time offset (`lib/time.ts`) — never local wall-clock trust; +5 min extend arrives as a `tournament-update` event and re-bases the tick.
- Optimistic: cart lines, block ±. **Never optimistic:** payments, shift close, print jobs, winner recording (bracket writes propagate server-side).
- Theme/font/accent apply instantly from local state and persist via `PUT /terminal-settings`; on load, settings render before first paint (inline script sets `data-theme` to avoid flash).
- Error envelope parsed once; domain codes → typed errors → design.md state tables. 401 → one silent refresh → hard logout.

## 5. Routing & guards

`middleware.ts` checks the auth cookie for `(app)` routes. Role guards server-side in guarded pages (redirect) AND API 403 regardless. Nav renders from one `NAV[role]` map. Tournament page renders for all roles; its manager rail and finance queries mount only for Manager+ (the finance endpoint 403s anyway).

## 6. Reaching the physical printer

| Option | Verdict |
|---|---|
| Browser print dialog | Rejected — driver reflow, human click per receipt, no queue/audit |
| WebUSB | Rejected — Chrome-only, permission prompts, no background retry |
| Separate local print agent | Rejected as a separate piece — |
| **Backend-directed printing** | **Chosen.** Spring Boot runs on the same cafe PC and owns the USB device, ESC/POS render, queue, retries, audit. The browser only POSTs print jobs and polls status |

A second counter terminal would still post jobs to the same venue backend.

## 7. Printer configuration UI

Setup (Admin) → "Printing" card: devices from `GET /printers`, default picker, 80/58 mm paper toggle, test-ticket button. Device choice persists server-side (venue fact, not browser fact).

## 8. Offline behavior

Cloud can drop; browser ↔ local backend practically cannot (same machine).
- **Fully works offline:** sessions, POS, payments (cash + manual TrxID), tournaments, printing, shift close. Sync chip shows "offline since HH:MM"; ops queue in the venue outbox.
- **Degraded:** phase-2 verified MFS initiation falls back to manual-reference with a notice.
- **Refresh mid-bill:** carts are server-side from the first line-add; nothing is lost.

## 9. Testing

- **Unit (Vitest + Testing Library):** money/time libs, Zod schemas, bill math, bracket rendering (N−1 matches, winner propagation display), permission NAV map, theme application.
- **Integration (Playwright, seeded backend):** the money paths — session → blocks → bill → split settle → ticket; counter sale; member redeem; tournament entry sale → auto-bracket at cap → start match → winner → champion; shift close with discrepancy incl. tournament line; print retry after simulated printer-offline.
- FE types generated from the backend OpenAPI (springdoc) in CI; drift fails the build. No snapshot tests; state-table coverage asserted per screen.
