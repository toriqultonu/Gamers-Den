import { expect, type Locator, type Page } from '@playwright/test';
import { STAFF } from './backend';

/**
 * The moves every scenario makes — signing in, reading an amount off a rail,
 * naming a thing so a re-run does not collide with the last one.
 *
 * Deliberately thin. A page object per screen would put a second description of
 * the UI beside the components' own, and the components already carry the
 * `data-testid` hooks design.md's state tables are asserted through
 * (`tests/state-coverage.test.tsx`). These helpers add only what a browser
 * needs and jsdom does not.
 */

/**
 * A suffix unique to this run, for anything the backend refuses twice — a
 * tournament name (409 `DUPLICATE_NAME`), a member phone (`DUPLICATE_PHONE`).
 * Re-running the suite against a database it already wrote to is a normal
 * developer move; colliding on a name is not a finding.
 */
export const RUN_ID = new Date()
  .toISOString()
  .replace(/[^0-9]/g, '')
  .slice(8, 14);

/**
 * Sign in through S1 and land on the role's own screen.
 *
 * By staff ID rather than the roster: the picker only remembers people this
 * browser profile has signed in as before, and a fresh Playwright context has
 * never seen anyone (`features/auth/staff-roster.ts`).
 */
export async function signIn(
  page: Page,
  staff: { id: number; pin: string } = STAFF.admin,
): Promise<void> {
  await page.goto('/login');

  // S1 shows the picker to a browser that has signed someone in before and the
  // bare staff-ID field to one that has not, so a second sign-in in the same
  // context (`08-shift-close` re-opens the till) meets a different screen.
  const roster = page.getByRole('radiogroup', { name: 'Staff' });
  const manual = page.getByLabel('Staff ID');
  await expect(roster.or(manual).first()).toBeVisible();
  if (await roster.isVisible()) {
    await page.getByRole('button', { name: /^Someone else/ }).click();
  }

  await manual.fill(String(staff.id));
  await page.getByLabel('PIN', { exact: true }).fill(staff.pin);
  // Exact: "Someone else — sign in by staff ID" is also a button on this screen.
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();

  // The landing screen for an Admin is S2; the shell is what proves the token
  // arrived, whichever screen it opened on.
  await expect(page.getByRole('navigation', { name: 'Main' })).toBeVisible();
}

/** Open a screen by route and wait for its root testid to render. */
export async function openScreen(page: Page, route: string, testId: string): Promise<Locator> {
  await page.goto(route);
  const screen = page.getByTestId(testId);
  await expect(screen).toBeVisible();
  return screen;
}

/**
 * `৳1,250` → `1250`, `−৳150` → `-150`.
 *
 * Amounts are asserted as numbers: the grouping, the sign glyph and the symbol
 * are `lib/money.ts`'s business and are unit-tested there. An e2e test that
 * matched the string would fail the day the sign becomes a real minus — which
 * it already is (U+2212).
 */
export function taka(text: string | null): number {
  const cleaned = (text ?? '').replace(/[৳,\s]/g, '').replace(/[−–—]/g, '-');
  const match = cleaned.match(/-?\d+/);
  if (!match) throw new Error(`no amount in ${JSON.stringify(text)}`);
  return Number(match[0]);
}

/** The amount rendered by one testid — `await amountOf(page, 'bill-due')`. */
export async function amountOf(page: Page, testId: string): Promise<number> {
  return taka(await page.getByTestId(testId).innerText());
}

/**
 * A `datetime-local` value in **venue** time, `minutesFromNow` out.
 *
 * The browser runs on `Asia/Dhaka` (playwright.config.ts), which is the venue,
 * so the local parts are the venue's wall clock — the same assumption
 * `lib/time.ts`'s `venueLocalInput` makes.
 */
export function venueDateTimeInput(minutesFromNow: number): string {
  const at = new Date(Date.now() + minutesFromNow * 60_000);
  const pad = (value: number) => String(value).padStart(2, '0');
  return (
    `${at.getFullYear()}-${pad(at.getMonth() + 1)}-${pad(at.getDate())}` +
    `T${pad(at.getHours())}:${pad(at.getMinutes())}`
  );
}

/** One chip of a `ChipSelect` — a button in the group its `label` names. */
export function chip(page: Page, group: string, label: string | RegExp): Locator {
  return page.getByRole('group', { name: group }).getByRole('button', { name: label });
}

/** One option of a `SegmentedChoice` — a radio in the group its `label` names. */
export function segment(page: Page, group: string, label: string | RegExp): Locator {
  return page.getByRole('radiogroup', { name: group }).getByRole('radio', { name: label });
}
