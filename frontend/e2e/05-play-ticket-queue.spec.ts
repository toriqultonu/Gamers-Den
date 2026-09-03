import { expect, test } from '@playwright/test';
import { VenueApi } from './support/backend';
import { RUN_ID, amountOf, chip, openScreen, segment, signIn, taka } from './support/app';

/**
 * **play-ticket sale while consoles busy → queue rail → seat → auto-loaded
 * timer → add time** (frontend/ARCHITECTURE.md §6, fifth scenario;
 * docs/bookings.md §3).
 *
 * The walk-up who arrives to a full floor. The venue sells them time anyway,
 * against a daily token, and seats them when a console frees up — which is the
 * whole reason the play queue exists and the reason **a play-ticket card is
 * never disabled for want of a console** (the POS's one card that ignores
 * availability; a stock item goes dark when the shelf is empty, an entry when
 * the event is full, a ticket never).
 *
 * What the rail refuses instead is seating a token nowhere can take: with every
 * console of that type busy the row's action reads "No free console" and is
 * off — the client twin of 409 `NO_FREE_CONSOLE`, and of
 * `CONSOLE_TYPE_MISMATCH` for a PS5 token carried to a PS4.
 */
test('a play ticket is sold to a full floor, then seated off the queue rail', async ({ page }) => {
  const api = await VenueApi.signIn();
  const consoles = await api.freeStations('PS4');
  expect(consoles.length, 'the seeded floor has two free PS4s').toBeGreaterThanOrEqual(2);
  const ticketPrice = await api.blockPrice('PS4');
  await api.dispose();

  const player = `E2E Walk-up ${RUN_ID}`;
  await signIn(page);

  /* ------------------------------------------------------------ fill the floor */

  await openScreen(page, '/floor', 'floor-screen');
  const panel = page.getByTestId('session-panel');
  for (const station of consoles) {
    await page.getByTestId('station-card').filter({ hasText: station.name }).click();
    await panel.getByTestId('start-session').click();
    await expect(panel).toHaveAttribute('data-variant', 'station');
  }

  /* -------------------------------------------- sell prepaid time anyway (S4) */

  await openScreen(page, '/pos', 'pos-screen');
  await segment(page, 'Bill', 'Counter sale').click();
  await chip(page, 'Menu category', 'Play ticket').click();

  // One 30-minute block, from the stepper over the ticket cards.
  const length = page.getByTestId('ticket-length');
  await length.getByRole('button', { name: 'Remove 30 minutes' }).click();
  await expect(length.getByTestId('time-stepper-length')).toContainText('30 min');

  const card = page.getByTestId('menu-card').filter({ hasText: 'Play ticket — PS4' });
  // Every PS4 is busy and the card is still live — that is the point (§3).
  await expect(card).toBeEnabled();
  await card.click();

  const bill = page.getByTestId('bill-panel');
  await bill.getByTestId('player-name').fill(player);
  await expect.poll(() => amountOf(page, 'bill-due')).toBe(ticketPrice);

  await page.getByTestId('settle').click();

  // The token is the customer's queue identity, printed and shown large.
  const tokens = page.getByTestId('queue-tokens');
  await expect(tokens).toBeVisible();
  await expect(tokens).toContainText(/TOKEN #\d{2}/);

  /* --------------------------------------------- the rail, with nowhere to sit */

  await openScreen(page, '/floor', 'floor-screen');
  const row = page.getByTestId('queue-row').filter({ hasText: player });
  await expect(row).toBeVisible();
  await expect(row).toContainText('30 min · prepaid');
  await expect(row.getByRole('button', { name: 'No free console' })).toBeDisabled();

  /* ------------------------------------------------- a console frees up, seat them */

  const freed = consoles[0];
  await page.getByTestId('station-card').filter({ hasText: freed.name }).click();
  // Nothing was bought on that walk-in, so there is nothing to settle.
  await expect(panel.getByTestId('end-session')).toBeEnabled();
  await panel.getByTestId('end-session').click();

  await row.getByRole('button', { name: `Seat on ${freed.name}` }).click();

  // Seated from the rail: the token's prepaid time is loaded onto the console
  // already paid for, so the seat owes nothing.
  await expect(panel).toHaveAttribute('data-variant', 'station');
  await expect(panel).toContainText('1 × 30 min bought');
  await expect.poll(async () => taka(await panel.getByTestId('bill-total').innerText())).toBe(0);

  // The auto-loaded timer: half an hour on the clock, started by staff.
  await expect(panel.getByTestId('countdown-clock')).toContainText('30:00');
  await panel.getByRole('button', { name: /the clock$/ }).click();
  await expect(panel).toHaveAttribute('data-state', 'running');

  /* ------------------------------------------------------------- add more time */

  await panel.getByRole('button', { name: '+30 min block' }).click();
  await expect(panel).toContainText('2 × 30 min bought');
  // The added half hour is ordinary billable time — the prepaid one is not.
  await expect
    .poll(async () => taka(await panel.getByTestId('bill-total').innerText()))
    .toBe(ticketPrice);

  // Given back before it is paid or played, the seat is square again.
  await panel.getByRole('button', { name: '−30 min block' }).click();
  await expect
    .poll(async () => taka(await panel.getByTestId('bill-total').innerText()))
    .toBe(0);

  await panel.getByTestId('end-session').click();
  await expect(panel).not.toHaveAttribute('data-variant', 'station');

  // The other console this scenario borrowed goes back as it was found.
  await page.getByTestId('station-card').filter({ hasText: consoles[1].name }).click();
  await panel.getByTestId('end-session').click();
  await expect(panel).not.toHaveAttribute('data-variant', 'station');
});
