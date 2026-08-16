# Gamer's Den — Design Specification

Product: point-of-sale and floor management for a console gaming cafe (2× PS5, 2× PS4, growing), plus tournament, pre-booking, and play-queue ticketing modules.
Users: cashier and manager at a staffed counter; owner (admin) on the same app.
This document is the authority for screens, states, components, tokens, and print templates.
The interactive prototype (`Gamers Den.dc.html`) is the visual reference and matches this spec.

Scope: single venue, one counter terminal (cafe PC), cloud sync for off-site owner access. Every screen below exists in the prototype; nothing here is new invention.

---

## 1. Screen inventory

| # | Screen | Purpose | Entry points | Roles |
|---|--------|---------|--------------|-------|
| S1 | Login / role select | Pick staff identity, enter 4-digit PIN, open shift. Left panel: brand statement, optionally over an admin-chosen background photo | App start, sign-out, shift close | All |
| S2 | Overview | Occupancy, revenue, avg ticket, net profit; horizontally scrolling live-station cards (click → Floor); pre-sold bookings stat; 30-day + day-of-week trends; stock watchlist; staff & shift closes; collapsible alerts rail (bell + unread badge) | Post-login landing (Admin) | Admin |
| S3 | Floor | Station cards with live countdowns — session blocks or tournament match time; session panel: start (no clock), ±30-min blocks, start/pause/resume clock, bill, end (blocked while net balance due); play-queue rail (who plays next, seat from queue); seat prompt for checked-in bookings ("Seat #NN «name» · 2 h prepaid" — loads prepaid blocks as already paid) | Sidebar; Overview card | All |
| S4 | Point of sale | Menu grid incl. Tournament and Play-ticket categories + bill panel; station bill or counter sale (toggle); member attach + points redemption; split payment; 80mm ticket preview. Play tickets sell prepaid queue tokens for any console type — sellable while consoles are busy | Sidebar (counter mode); Floor "Bill & take payment" / "Add food & drinks" (station mode) | All |
| S5 | Inventory | Read-only stock table, low-stock rail, equipment register | Sidebar | All |
| S6 | Members | Search/table, member detail (wallet, points, visits), top-up, redeem-to-wallet | Sidebar | All |
| S6a | New member (dialog) | Register; optional opening top-up; "Save & seat on «station»" starts a session | S6 button | All |
| S7 | Shift close | X-report by method; tournament-entries and pre-booking reconciliation strips; editable drawer count with live discrepancy; petty-cash list; Z print closes shift → login | Sidebar | All |
| S8 | Expenses | Petty-cash table + record form (description, amount, category chips); totals feed net profit everywhere | Sidebar | All |
| S9 | Reports | KPIs, 14-day stacked trend, per-station utilisation, busiest hours, top sellers | Sidebar | Admin, Manager |
| S10 | Setup / Menu & stock | Admin: stations, pricing, staff (add cashier/manager with PIN, remove unless on shift), menu, **pre-booking controls** (enable/disable, package fee, cancellation cutoff hours). Manager: menu & stock only | Sidebar | Admin, Manager |
| S11 | Print preview | Exact character-grid render of any artifact; reprint with reason | POS settle, shift close, job history | All |
| S12 | Tournaments | Live & upcoming / History tabs; bracket with per-match countdowns; "Now on «console»" live tiles; match board; Manager rail: arrange/cancel, station blocks, finance analytics. Cashier rail: read-only guidance + POS shortcut | Sidebar (badge LIVE) | All (writes Manager+) |
| S13 | Settings | Theme (dark default / light), text size, accent color; login-panel background image; alert sound; auto-lock; receipt copies; profile avatar color | Sidebar | All |
| S14 | Bookings | Pre-booking desk. Main: **Upcoming / History tabs** over the bookings table (upcoming = paid & waiting, with count; history = checked-in, played, cancelled); rate card; row click selects. Right rail: idle state = one **"New booking"** button + hint; selected row = **booking detail** (customer, console, starts, play time, paid, status; "Check in & print token"; token confirmation + thermal stub; "Cancel & refund" or cutoff lock note); "New booking" = pay-first form (console chips, member attach or name/phone, start time, **−30/+30 play-time stepper**, live bill box: play time at console rate + package fee = total paid now, payment method, "Take ৳N & confirm booking") | Sidebar (hidden when admin disables pre-booking) | All (settings Admin) |

### Key flows

**Pre-booking** (admin-controlled: enable/disable, package fee, cancellation cutoff — S10):
1. Customer pays the FULL amount up front: play time (n × 30-min blocks at the console's rate) + package fee.
2. Cancellation: allowed with full refund only ≥ cutoff hours before start; inside the window the action locks with an explanatory note.
3. Arrival: staff opens the booking on S14 → "Check in & print token" — assigns the next daily queue token, prints the PLAY TICKET stub, status becomes "Token #NN · waiting — seat from Floor", and the row moves to History.
4. Seating: on S3 the booked console (when free) offers "Seat #NN «name» · 2 h prepaid" — seating loads the prepaid blocks as already-paid, the clock starts when they play; +30 min adds billable time.

**Play-queue ticketing** (walk-up prepaid tokens):
- POS sells "Play tickets" (console type + length) even when all consoles are busy; each sale issues a sequential token (resets daily) and enters the play queue.
- The Floor's queue rail lists who plays next; staff seats any waiting token onto a free console — the timer auto-loads the token's prepaid time; more time can be added at the console.
- Token numbers print on the thermal stub ("Tokens reset daily").

### State coverage per screen

All screens implement: default, loading (skeletons matching layout), empty, error (never destroys entered data), permission-denied (UI hides affordance AND API 403 renders as an access notice). Non-obvious cases:

| Screen | Empty | Error | Permission-denied |
|--------|-------|-------|-------------------|
| S1 | n/a (seed Admin) | "Wrong PIN" inline; 5-try lockout | n/a |
| S2 | "No sessions yet today" tiles | Stale-data banner + last-sync time | Non-admin → redirect S3 |
| S3 | "No stations — add one in Setup"; queue rail: "No one waiting" | Controls disabled + banner | Reserved stations refuse walk-in session start |
| S4 | "Menu is empty" | Settle failure keeps bill intact, retry | n/a |
| S9 | "Not enough data yet" per chart | Banner | Cashier: hidden + guarded |
| S10 | n/a | Inline field errors | Cashier hidden; Manager sees menu/stock only |
| S12 | "No tournaments scheduled" | Winner-record failure banner | Cashier: config controls absent; finance endpoint 403 |
| S14 | Upcoming: "No upcoming bookings — take one with New booking."; History: "No past bookings yet." | Confirm failure keeps the form | Feature disabled → nav item hidden; API 409 `PREBOOKING_DISABLED` |

Global: persistent sync chip (synced / syncing / offline since HH:MM); every screen usable at 1366×768.

---

## 2. Component inventory

| Component | Variants | States | Key props |
|-----------|----------|--------|-----------|
| Button | primary, secondary, ghost, icon, block | default, hover, active, focus-visible, disabled, loading | `variant, size, loading, disabled` — labels centered on full-width actions |
| Tag | accent, neutral, outline | static | `variant` |
| StationCard | active, open, paused, locked, free, reserved (tournament), booked (checked-in arrival), maintenance | selected outline, hover | `station, selected, onSelect` |
| SessionPanel | station, reserved, seat-prompt, empty | running, paused, open, locked, match | `station` |
| CountdownClock | panel (50px), card (52px), match tile (26px) | running, paused, overtime, none | `remainingSec, state` |
| MenuItemCard | item, tournament entry, play ticket | hover, low-stock, out-of-stock/full (disabled) | `item, onAdd` |
| BillPanel | station, counter | with/without member, tournament/ticket lines | `mode, sessionId?` |
| QueueRail / QueueRow | — | waiting, called; seat action disabled when no free console of type | `entry, onSeat` |
| BookingTable + tabs | upcoming, history | row selected (accent outline), clickable | `tab, rows, onPick` |
| BookingDetail (rail) | paid, arrived (token + stub), played, cancelled | check-in, cancel, cutoff-locked note | `booking` |
| BookingForm (rail) | — | console/member/method chips, time stepper, live bill box, confirm with computed total | `settings` |
| TimeStepper | — | −30 disabled at 30 min | `blocks, onChange` |
| TokenBadge | inline, stub | — | `token` |
| MemberSearch | collapsed, results, attached, auto-attached | no-match notice | `onAttach` |
| RedeemStepper | — | None/100/200/Max | `max, value, onChange` |
| PaymentSplit | cash, bkash, nagad, wallet | selected, amounts | `due, methods, onChange` |
| ReceiptPreview | receipt, z/x-report, tournament stub, play-ticket stub | rendering, ready, failed | `printJobId` |
| BracketView / MatchBox / LiveMatchTile / MatchBoard / ChampionBanner / FinancePanel | per tournaments.md | — | — |
| DataTable, FieldInput, SegmentedChoice, ChipSelect, Dialog, SidebarNav, StatTile, BarChart, ProgressBar, AlertCard, AlertsRail (bell/expanded), SyncChip, TopBar, AvatarSwatch, ImagePicker | — | — | — |

Hand-rolled primitives in `src/components/ui`, domain components in `src/components/domain`. No third-party visual library — the Modernist look (0 radius, 2px rules, flush structure) is built directly on the tokens.

---

## 3. Design tokens

Semantic names; values are the Modernist palette (`_ds/.../styles.css`). **Dark is the default theme** (dim venue); light is a Settings toggle. Theme, text size and accent are per-terminal.

### Color

| Token | Light | Dark | Usage |
|-------|-------|------|-------|
| `color.bg` | `#f3f2f2` | `#171514` | App ground |
| `color.surface` | `#eceaea` | `#211f1e` | Sidebar, rails, panels |
| `color.card` | `#ffffff` | `#2a2725` | Active station cards, unread alerts, live match tiles |
| `color.text` | `#201e1d` | `#f3f2f2` | Primary text |
| `color.divider` | `#d8d5d3` | `#3c3835` | 2px structural rules |
| `color.accent` | user-selectable: Den Red `#ec3013` (default), Blue `#0f62fe`, Green `#198038` | same | Primary action, live marks, token badges, reserved borders |
| `color.accent-strong` | ramp 700 of chosen accent | brightened equivalent (e.g. `#ff7a5c`) | Accent-colored body text |
| `color.accent-tint` | ramp 100 | dark equivalent (e.g. `#42150e`) | Locked-station fill, tags |
| `color.track` / `color.bar-alt` | neutral-300 / neutral-700 | `#343130` / `#b5b0ab` | Progress, second chart series |
| `color.on-accent` | `#ffffff` | `#ffffff` | Text on accent |
| `color.paper` | `#ffffff` fixed | fixed | Receipt/stub previews only |

Contrast rules (WCAG AA): body copy never in raw accent — use `accent-strong` (≥7:1 both themes). `on-accent` on accent is AA for large/UI text only. Dark theme must override the full tonal ramp (100–900 for accent and neutral) so tags and tinted fills stay readable; inputs and secondary/ghost buttons get explicit dark backgrounds (no browser defaults).

### Typography

Archivo throughout; headings weight 800, tight tracking. Base size scales with the Settings text-size choice: Compact 13px / Default 14px / Large 16px.

| Token | Size/line | Usage |
|-------|-----------|-------|
| `type.display` | 50–52/1.05, −0.045em | Session clocks |
| `type.h1` | 34–36/1.1 | Panel titles, big stats |
| `type.h2` | 23/1.15 | Screen title |
| `type.h3` | 17–18/1.2 | Card titles |
| `type.body` | 13–14/1.5 | Default |
| `type.label` | 9–10/1.2, +0.14em, uppercase | Kickers, table headers |
| `type.mono` | 10–11/1.5 ui-monospace | Receipt/stub previews only |

Bills, clocks and tables use `font-variant-numeric: tabular-nums`.

### Spacing, radii, elevation, states

`space.1..8` = 4, 8, 12, 16, 20, 22, 32, 56 px. Radius 0 everywhere. `rule.strong` 2px / `rule.hair` 1px in `divider`. Shadows only on the app frame, dialogs, receipt preview. `:focus-visible` = 2px accent outline; hover = one ramp step; disabled = 45% opacity.

---

## 4. Responsive behavior

Counter terminal is fixed desktop (min 1366×768, designed at 1440×900).

| Breakpoint | Behavior |
|------------|----------|
| ≥1280 | Full three-column layouts |
| 1024–1279 | POS ticket column collapses behind a Preview button; Overview alerts rail starts collapsed; Bookings rail overlays |
| 768–1023 | Sidebar → icons; 1-up station grid; bill panel becomes drawer (owner-on-tablet, not an operating target) |
| <768 | "Use a larger screen" notice |

---

## 5. Print template inventory

Staffed counter, USB thermal ESC/POS (**assumed 80mm/203dpi; 58mm is a config switch**, see §8). Monochrome character grid (48 cols Font A), independent of screen theme by construction. Full cut + 4 feeds per artifact.

### P1 — Sale ticket
Header (GAMER'S DEN double-size, address/phone) · meta rows (TXN, STATION or TYPE "Counter sale", IN, OUT, CASHIER) · lines (`GAMING n×30M`, F&B `NAME ×qty`, `POINTS nPTS` negative) · TOTAL double-height + one row per tender (bKash/Nagad append last 6 of TrxID) · loyalty line (points earned · balance) · Code 128 barcode · reprint band when reprinted.

### P2 / P3 — Z / X report
Z: shift id, opened/closed, operator, float, takings method × (gaming, F&B, tournament, **pre-booking**, total), expected vs counted vs discrepancy (double-height), expenses, counts, signature line, barcode = shift id. X: same minus drawer/discrepancy/signature, headed "X REPORT — INTERIM", no barcode.

### P4 — Expense voucher
Date-time, description, category, amount double-height, recorded-by, signature line.

### P5 — Tournament entry stub
Inverted "TOURNAMENT ENTRY" band · tournament name(s) · player name · **TOKEN #NN** double-height · QR code · "Show this ticket at the bracket desk".

### P6 — Play ticket (queue token)
Two sources, same layout: a POS play-ticket sale, or a booking check-in (headed "PLAY TICKET — PREBOOKED"). Header · band · Player name · **TOKEN #NN** double-height + console type and prepaid length (`Titan · 2 H PREPAID`) · "Tokens reset daily" · Code 128 barcode = entry id. Printed at S14 check-in or with the P1 sale ticket.

### P7 — Booking confirmation
Printed with the booking sale (part of P1): BOOKING band, console, start time, play time, package fee line, cancellation policy line ("Full refund until N h before start").

### Barcode / QR
- Code 128 subset B (transaction / shift / queue-entry ids): module ≥0.33mm, height 12mm, quiet zone ≥10 modules, native `GS k`.
- QR (P5 only): model 2, ECC M, module ≥0.5mm, quiet zone 4 modules, content = opaque `qr_token` (no PII), native `GS ( k`.

### S11 Print preview
Shows the stored render (never recomputed). States: rendering, ready (Print/Reprint), queued (printer offline), failed (retry), reprint-mode (reason: LOST, DAMAGED, CUSTOMER_COPY, DISPUTE — required).

---

## 6. Settings (S13) spec

| Group | Control | Values | Scope |
|---|---|---|---|
| Appearance | Theme | Dark (default) / Light | Terminal |
| | Text size | Compact / Default / Large | Terminal |
| | Accent color | Den Red / Blue / Green (full ramp swaps per theme) | Terminal |
| Login screen | Background image | pick file / remove; dark overlay keeps type readable | Terminal |
| Terminal | Alert & time-up sound | on/off | Terminal |
| | Auto-lock (PIN to unlock) | Off / 2 / 5 / 10 min | Terminal |
| | Receipt copies | 1 / 2 | Terminal |
| Profile | Avatar color | 6 swatches + reset | Per staff login |

Pre-booking controls live in **Setup (S10), Admin only**: feature on/off, package fee (৳), cancellation cutoff (hours).

## 7. Iconography & imagery

Lucide only (bell, printer, plus/minus, pause/play, search, log-out, alert-triangle, wifi-off, trophy, calendar-clock, ticket). No emoji. The only photograph is the optional login background, shown under a dark overlay.

## 8. Open flags (not guessed)

1. Printer model unconfirmed — 80mm/203dpi/ESC/POS assumed; 58mm profile is config.
2. VAT: omitted per owner decision; P1 reserves space for a future VAT row.
3. Morning-discount window (−25%, 10:00–14:00) — confirm hours.
4. Booking start times are free-text in the prototype; production should use a date-time picker with conflict warnings (same console, overlapping prepaid window) — warning only, staff can override.
