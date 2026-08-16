# Gamer's Den — Frontend Architecture

Stack (fixed): Next.js App Router + TypeScript. Runs on the cafe PC in a browser; same app served to the owner via cloud. Every choice minimizes moving parts. `api-contract.md` is the authority for data shapes.

---

## 1. Supporting libraries

| Concern | Choice | Rationale | Rejected alternative |
|---|---|---|---|
| Styling | Tailwind CSS 4 | Token-driven (`@theme` maps 1:1 to design.md tokens incl. the dark-default ramp and the three accent ramps), zero runtime | CSS Modules — no token enforcement; styled-components — runtime, poor RSC fit |
| Component layer | Hand-rolled primitives + `class-variance-authority` | ~35 small components; a library would be fought on radius-0, 2px rules, density | shadcn/ui — restyling cost exceeds writing Button/Dialog/Table directly |
| Server state | TanStack Query v5 | Caching, retries, optimistic updates, `refetchOnReconnect`; SSE feeds `setQueryData` | RTK Query — drags Redux; SWR — weaker mutations |
| Client state | Zustand (one store) | Only true client state: selected station, POS mode, bill draft flags, alerts-rail open, selected tournament, bookings tab/selection/form-open | Redux Toolkit — ceremony |
| Forms + validation | react-hook-form + Zod | Schemas shared with API DTO parsing (`z.infer`); many small forms (member, expense, staff, item, tournament, booking) | Formik — heavier |
| HTTP | Native `fetch` wrapper (`lib/api.ts`) | Auth header, Idempotency-Key, error envelope, 401→refresh retry (~80 lines) | axios |
| Charts | Plain SVG/divs | Bars and progress rows only | recharts — bundle + off-style |
| Dates | date-fns + `@date-fns/tz` | `Asia/Dhaka`; day-rollover token logic | dayjs |
| Barcode/QR preview | none | S11 shows the server's stored render — no client drawing, no divergence | jsbarcode / qrcode |

## 2. Folder structure

```
src/
  app/
    (auth)/login/page.tsx            # S1 (client; bg image from terminal settings)
    (app)/layout.tsx                 # shell: sidebar (role-aware NAV map; Bookings item gated by booking_settings.enabled), topbar, sync chip
    (app)/overview/page.tsx          # S2 (admin guard)
    (app)/floor/page.tsx             # S3 (+ queue rail, seat prompts)
    (app)/pos/page.tsx               # S4
    (app)/bookings/page.tsx          # S14 (tabs + table + right rail: idle / detail / form)
    (app)/inventory/page.tsx         # S5
    (app)/members/page.tsx           # S6 (+ NewMemberDialog)
    (app)/tournaments/page.tsx       # S12
    (app)/shift/page.tsx             # S7
    (app)/expenses/page.tsx          # S8
    (app)/reports/page.tsx           # S9 (manager+ guard)
    (app)/setup/page.tsx             # S10 (role-sectioned; booking controls admin-only)
    (app)/settings/page.tsx          # S13
    (app)/print/[jobId]/page.tsx     # S11
  components/
    ui/button.tsx                    # primitives per design.md §2
    domain/station-card.tsx          # StationCard, SessionPanel, BillPanel, BracketView,
                                     # BookingTable, BookingDetail, BookingForm, TimeStepper,
                                     # QueueRail, TokenBadge, ...
  features/
    sessions/{queries,mutations}.ts
    pos/bill-store.ts
    payments/schemas.ts
    bookings/{queries,mutations}.ts      # settings, create, check-in, cancel, seat
    queue/{queries,mutations}.ts         # play tickets, seat, remove
    tournaments/{queries,mutations}.ts
    printing/use-print-job.ts
    settings/use-terminal-settings.ts    # theme/font/accent applied as html[data-theme] + CSS vars
    sync/use-sync-status.ts
  lib/
    api.ts · sse.ts · money.ts · time.ts # server-offset clock for ALL countdowns (sessions + matches)
  styles/tokens.css
middleware.ts
```

## 3. Server vs Client Components

Pages and layout shells are Server Components; anything with a timer, subscription, form, or optimistic mutation is a Client Component. Floor, POS, Bookings (rail state), Tournaments, SessionPanel, clocks, forms, alerts rail, Settings = client. Menu/stations/terminal-settings/booking-settings prefetched server-side into the hydration boundary.

## 4. Data, caching, errors

- Query keys namespaced (`['sessions', id]`, `['bookings', tab]`, `['queue']`…). Live data hydrates from SSE (`station-update`, `queue-update`, `booking-update`); `staleTime` 10 s with polling fallback.
- All countdowns tick client-side from `remainingSeconds` + a server-time offset (`lib/time.ts`) — never local wall-clock trust.
- The booking form's bill box (play time × console rate + package fee) computes client-side from cached pricing + settings for instant feedback; the server re-prices at confirm and is authoritative — a drift renders the server total with a notice, never a silent charge.
- Optimistic: cart lines, block ±, bookings tab switch. **Never optimistic:** payments, booking create/cancel/check-in, seat-from-queue, shift close, print jobs, winner recording.
- Theme/font/accent apply instantly from local state and persist via `PUT /terminal-settings`; inline script sets `data-theme` before first paint (no flash).
- Error envelope parsed once; domain codes → typed errors → design.md state tables (`CANCEL_CUTOFF_PASSED` renders the lock note, `PREBOOKING_DISABLED` hides the nav + notice). 401 → one silent refresh → hard logout.

## 5. Routing & guards

`middleware.ts` checks the auth cookie for `(app)` routes. Role guards server-side in guarded pages (redirect) AND API 403 regardless. Nav renders from one `NAV[role]` map filtered by feature flags (Bookings hidden when disabled). Tournament page renders for all roles; manager rail and finance queries mount only for Manager+.

## 6. Reaching the physical printer

| Option | Verdict |
|---|---|
| Browser print dialog | Rejected — driver reflow, human click per receipt, no queue/audit |
| WebUSB | Rejected — Chrome-only, permission prompts, no background retry |
| Separate local print agent | Rejected as a separate piece |
| **Backend-directed printing** | **Chosen.** Spring Boot runs on the same cafe PC and owns the USB device, ESC/POS render, queue, retries, audit. The browser only POSTs print jobs and polls status |

A second counter terminal would still post jobs to the same venue backend.

## 7. Printer configuration UI

Setup (Admin) → "Printing" card: devices from `GET /printers`, default picker, 80/58 mm paper toggle, test-ticket button. Device choice persists server-side (venue fact, not browser fact).

## 8. Offline behavior

Cloud can drop; browser ↔ local backend practically cannot (same machine).
- **Fully works offline:** sessions, POS, payments (cash + manual TrxID), bookings, play queue, tournaments, printing, shift close. Sync chip shows "offline since HH:MM"; ops queue in the venue outbox.
- **Degraded:** phase-2 verified MFS initiation falls back to manual-reference with a notice.
- **Refresh mid-bill:** carts are server-side from the first line-add; nothing is lost. Bookings rail state (tab, selection) is client-only and safely resets.

## 9. Testing

- **Unit (Vitest + Testing Library):** money/time libs, Zod schemas, bill math incl. prepaid credit, booking bill-box math (blocks × rate + fee), token badge/daily-reset display, bracket rendering, permission NAV map + feature-flag filter, theme application.
- **Integration (Playwright, seeded backend):** the money paths — session → blocks → bill → split settle → ticket; counter sale; member redeem; **booking create → cancel-refund at/inside cutoff → recreate → check-in token → seat from Floor → prepaid clock**; **play-ticket sale while consoles busy → queue rail → seat → auto-loaded timer → add time**; tournament entry → bracket → winner; shift close with discrepancy incl. tournament + pre-booking lines; print retry after simulated printer-offline.
- FE types generated from the backend OpenAPI (springdoc) in CI; drift fails the build. No snapshot tests; state-table coverage asserted per screen.
