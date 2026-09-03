import { expect, test } from '@playwright/test';
import { VenueApi } from './support/backend';
import { openScreen, signIn, taka } from './support/app';

/**
 * **shift close with discrepancy, incl. tournament + pre-booking lines**
 * (frontend/ARCHITECTURE.md §6, seventh scenario) — and the till re-opened
 * behind it.
 *
 * Everything the seven scenarios before this took has landed on one shift, so
 * this is where the money path is added up. The two reconciliation strips are
 * the reason the scenarios run in the order they do: **tournament entries** and
 * **pre-booking deposits** are money the venue holds for something that has not
 * happened yet, so S7 states them separately from the takings they are part of
 * — a drawer that balances on the day of an event balances *because* those
 * lines are read, not despite them.
 *
 * The count is deliberately wrong. A discrepancy is the case the screen exists
 * for: the figure updates under the operator's fingers, the warning says what
 * closing will do, and the Z the server writes carries the same three numbers
 * the screen previewed. Nothing here is optimistic — the close snapshots the Z,
 * queues the P2, raises the alert and signs the terminal out in one
 * transaction, and only then is the report on screen the server's own.
 *
 * It ends by opening a fresh till with the same float, because the suite has to
 * leave the venue as it found it: every money scenario needs an open shift, and
 * this one closes the shift they all posted to.
 */
test('the shift closes over a discrepancy, and the till re-opens behind it', async ({ page }) => {
  const api = await VenueApi.signIn();
  const report = await api.get<{
    openingFloat: number;
    cash: { expected: number };
    takings: { totals: Record<string, number> };
  }>('/shifts/current/x-report');
  await api.dispose();

  const expected = report.cash.expected;
  const float = report.openingFloat;
  /** Counted over: the drawer has more in it than the till says it should. */
  const over = 500;

  await signIn(page);
  await openScreen(page, '/shift', 'shift-screen');

  /* --------------------------------------------- what is being reconciled */

  const tournament = page.getByTestId('strip-tournament');
  const booking = page.getByTestId('strip-booking');
  await expect(tournament).toContainText('Tournament entries this shift');
  await expect(booking).toContainText('Pre-booking deposits this shift');
  // The entries `06` sold and the bookings `04` took are in the drawer, and
  // both strips say so rather than leaving them inside one takings total.
  expect(taka(await tournament.innerText())).toBeGreaterThan(0);
  expect(taka(await booking.innerText())).toBeGreaterThan(0);

  await expect(page.getByTestId('takings')).toBeVisible();
  expect(await page.getByTestId('takings-total').innerText()).toBeTruthy();

  /* ------------------------------------------------------- count it wrong */

  const drawer = page.getByTestId('drawer');
  await expect(drawer).toHaveAttribute('data-state', 'uncounted');
  await expect(drawer.getByTestId('discrepancy')).toHaveText('—');

  await page.getByTestId('shift-rail').getByLabel('Notes & coins counted (৳)').fill(
    String(expected + over),
  );

  // The subtraction the operator would otherwise do on the back of a receipt.
  await expect(drawer).toHaveAttribute('data-state', 'over');
  expect(taka(await drawer.getByTestId('discrepancy').innerText())).toBe(over);
  await expect(page.getByTestId('discrepancy-warning')).toContainText('raises a cash alert');

  /* ------------------------------------------------------------- the Z */

  // The Z itself, caught on the wire. The closed panel that renders it is
  // deliberately a blink — it signs the terminal out as it appears — so the
  // assertion is made against the server's own answer rather than against a
  // screen that is on its way to S1.
  const z = page.waitForResponse(
    (response) =>
      response.url().includes('/shifts/current/close') && response.request().method() === 'POST',
  );
  await page.getByTestId('close-shift').click();

  const closing = (await (await z).json()) as {
    cash: { expected: number; counted: number; discrepancy: number };
    printJobId?: number;
  };
  // Recomputed inside the closing transaction — the screen's preview, confirmed.
  expect(closing.cash.expected).toBe(expected);
  expect(closing.cash.counted).toBe(expected + over);
  expect(closing.cash.discrepancy).toBe(over);
  // The Z is on paper: one print job, queued in the same transaction (P2).
  expect(closing.printJobId).toBeGreaterThan(0);

  // Closing signs the terminal out — the till is shut and nobody is on it.
  await expect(page).toHaveURL(/\/login(\?|$)/);

  /* --------------------------------------------- open the next one behind it */

  await signIn(page);
  await page.goto('/shift');
  const noShift = page.getByTestId('no-shift');
  await expect(noShift).toBeVisible();
  await expect(noShift).toContainText('No shift is open on this terminal');

  await noShift.getByLabel('Opening float (৳)').fill(String(float));
  await noShift.getByTestId('open-shift').click();

  await expect(page.getByTestId('shift-screen')).toBeVisible();
  await expect(page.getByTestId('drawer')).toHaveAttribute('data-state', 'uncounted');
});
