# Gamer's Den — frontend

Next.js App Router + TypeScript terminal app. Read `ARCHITECTURE.md` (canonical
folder map, query keys, invariants) before adding anything; `docs/design.md` is
the authority for screens, states, components and tokens.

```bash
npm install
npm run dev        # http://localhost:3000
npm run build      # production build — the task gate
npm test           # Vitest + Testing Library — the task gate
npm run typecheck  # tsc --noEmit
npm run types:gen  # regenerate the API types from a running backend
npm run types:check # CI gate: fail when they drift from /v3/api-docs
```

## Talking to the backend

`src/lib/api.ts` is the only place this app calls the venue backend. It attaches
the bearer token, attaches an `Idempotency-Key` to the money/print routes
`docs/api-contract.md` §1 guards, parses the error envelope once into a typed
`ApiError`, and handles a 401 with exactly one silent refresh before signing
out. Screens switch on `error.code` (`CANCEL_CUTOFF_PASSED`,
`PREBOOKING_DISABLED`, `CONSOLE_TYPE_MISMATCH`, …) and render `errorNotice()`.

**One key per user intent, reused on retry.** Pass `intent` on a guarded call —
`api.post('/payments', body, { intent: 'settle:session:41' })`. The key is held
until that intent succeeds, so a retry after a timeout replays the server's
stored answer instead of charging twice. A guarded call with no intent throws
before it reaches the network.

```
src/lib/api-types.ts   generated from /v3/api-docs — never edit
openapi.json           the spec snapshot those types were cut from
src/lib/query-keys.ts  the canonical keys of ARCHITECTURE.md §4.1
src/lib/query-client.ts  retry/staleness policy; mutations never auto-retry
src/lib/time.ts        the server-offset clock every countdown ticks from
src/lib/money.ts       integer BDT
```

Regenerating the types needs a running backend
(`cd backend && ./mvnw spring-boot:run`); `npm run types:gen` rewrites both
files and `npm run types:check` is what CI runs to fail the build on drift.
`OPENAPI_URL` overrides the default `http://localhost:8080/v3/api-docs`, and
`NEXT_PUBLIC_API_BASE_URL` points the app itself at a backend other than
`http://localhost:8080/api/v1`.

**Clocks are the server's.** Nothing in the UI may read `Date.now()` for a
countdown: `lib/api.ts` measures the offset from every response's `Date` header
and `useCountdown(snapshot)` re-derives the remainder each tick, so a terminal
with a wrong clock still shows the right time left.

## The design tokens

`src/styles/tokens.css` holds every `docs/design.md` §3 token as a `--gd-*`
custom property, layered by `[data-theme]` × `[data-accent]` × `[data-text-size]`.
`src/app/globals.css` maps those 1:1 onto Tailwind 4 theme tokens with
`@theme inline`, so `bg-surface`, `text-accent-strong`, `border-divider` and
friends re-resolve inside any re-themed subtree.

- **Dark is the default.** `<html>` is server-rendered with
  `data-theme="dark" data-accent="red" data-text-size="default"`; the inline
  script in `src/app/layout.tsx` re-applies the terminal's saved choice before
  first paint.
- **`/tokens`** renders the whole system — both themes across all three accents,
  the type scale, spacing and structure. `tests/tokens.test.tsx` pins the
  stylesheet against `src/styles/tokens.ts`, so a token cannot drift silently.
- **The spacing scale is the design's, not Tailwind's default.** `space.1..8`
  is `4 · 8 · 12 · 16 · 20 · 22 · 32 · 56` px, so `p-6` is 22px and `p-8` is
  56px. Numbers outside 1–8 fall back to Tailwind's `0.25rem` step.
- **Radius is 0 everywhere** and rules are 2px (`rule`, `border-rule`) or 1px
  (`rule-hair`, `border-hair`). Bills, clocks and tables take `tabular`.

`src/styles/tokens.ts` mirrors the CSS for anything that needs the values in
TypeScript. Change a value in both, or the token test fails.

## A note on this checkout's drive

This repo sits on an **exFAT** volume, where Node's `readlink` answers `EISDIR`
for an ordinary file instead of the `EINVAL` every other filesystem returns.
Webpack's resolver treats anything but `EINVAL` as fatal, so `next build` dies
on the first file it inspects.

`scripts/exfat-readlink.cjs` rewrites that one error code — exFAT cannot hold
symlinks at all, so `EINVAL` is the truthful answer — and `scripts/run.mjs`
installs it via `--require` before Node loads anything else. Both self-detect
and do nothing on a filesystem that behaves, which is why the npm scripts go
through the launcher. Moving the checkout to an NTFS volume makes the whole
arrangement inert; nothing else in the app depends on it.
