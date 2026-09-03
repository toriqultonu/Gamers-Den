import { defineConfig, devices } from '@playwright/test';

/**
 * TASK F17 — the release gate.
 *
 * Playwright drives the built app against a **seeded dev backend** (TASKLIST
 * B22): a real Spring Boot on a real Postgres with the demo floor, menu,
 * members, open till, paid booking and waiting token already in it. Nothing
 * here stubs the API. The one exception is documented where it happens —
 * `e2e/07-print-retry.spec.ts` simulates the printer being off the bus, which
 * is a USB fact no HTTP client can assert from outside the JVM.
 *
 * Three settings carry the whole design and none of them is a preference:
 *
 * **`workers: 1` and `fullyParallel: false`.** The specs share one venue. They
 * open sessions on its consoles, sell from its stock, take tokens off its daily
 * counter and finally close its till, and they are numbered in the order the
 * money moves — `01…08`, with the shift close last because it signs the
 * terminal out. Two workers on one seeded backend would be two cashiers
 * fighting over the same drawer.
 *
 * **`retries: 0`, in CI too.** A retried UI test replays real writes: the
 * second attempt starts on a floor the first one already charged, seated and
 * printed for. A flake here has to be read, not re-rolled — that is the price
 * of testing the money path against the real thing rather than a mock.
 *
 * **1440×900.** design.md §4's three-column desktop, which is what the counter
 * PC runs. The 1280/768 collapses are covered by `tests/responsive.test.tsx`
 * (F16) at unit speed; re-driving them through a browser would buy nothing.
 *
 * Running it locally:
 *
 *   docker compose -f ../backend/docker-compose.yml up -d   # Postgres
 *   cd ../backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
 *   cd ../frontend && npm run build && npm run e2e
 *
 * `npm run e2e:reset` throws the dev database away when a previous run has left
 * the venue in a state a fresh scenario would trip over.
 */

/** Where the built app is served. The `webServer` below starts it when it is not already up. */
const BASE_URL = process.env.E2E_BASE_URL ?? 'http://localhost:3000';

export default defineConfig({
  testDir: './e2e',
  // One venue, one cashier: see the header.
  fullyParallel: false,
  workers: 1,
  retries: 0,
  // A money-path scenario is a dozen server round trips; the default 30 s is
  // tight for the ones that settle twice.
  timeout: 90_000,
  expect: { timeout: 10_000 },
  // Nothing may be committed with a `test.only` left in it.
  forbidOnly: Boolean(process.env.CI),
  reporter: process.env.CI
    ? [['github'], ['html', { open: 'never' }], ['list']]
    : [['list'], ['html', { open: 'never' }]],
  outputDir: 'test-results',

  // Fails fast, with instructions, when the backend is missing or unseeded —
  // an unreadable cascade of 12 timeouts helps nobody.
  globalSetup: './e2e/support/global-setup.ts',

  use: {
    baseURL: BASE_URL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
    // The venue's own timezone and money locale, so `formatVenueDateTime` and
    // the datetime-local pickers read the same here as on the counter PC.
    timezoneId: 'Asia/Dhaka',
    locale: 'en-US',
  },

  projects: [
    {
      name: 'counter',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } },
    },
  ],

  webServer: {
    // `next start`, not `next dev`: CI tests the artefact it just built, and
    // `scripts/run.mjs` installs the exFAT readlink shim the build needs.
    command: 'node scripts/run.mjs next start',
    url: BASE_URL,
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
    stdout: 'pipe',
    stderr: 'pipe',
  },
});
