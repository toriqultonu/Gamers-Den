import { expect, test } from '@playwright/test';
import { VenueApi } from './support/backend';
import { RUN_ID, amountOf, chip, openScreen, signIn, taka, venueDateTimeInput } from './support/app';

/**
 * **booking create → cancel-refund at/inside cutoff → recreate → check-in token
 * → seat from Floor → prepaid clock** (frontend/ARCHITECTURE.md §6, fourth
 * scenario; docs/bookings.md).
 *
 * The whole pre-booking life, in the order a venue lives it, and every step is
 * a rule rather than a click:
 *
 *  - **pay first.** There is no held slot: `POST /bookings` takes the money,
 *    writes the row and queues the confirmation in one transaction.
 *  - **the cutoff is a real lock.** Outside it, Cancel & refund is there and
 *    gives everything back. Inside it the button is gone and a note stands in
 *    its place — the client twin of 409 `CANCEL_CUTOFF_PASSED`.
 *  - **the token is the server's.** Check-in draws the next daily number off a
 *    row-locked counter and prints the P6 stub; nothing is drawn ahead of the
 *    response.
 *  - **prepaid means prepaid.** Seating loads the booked blocks as *already
 *    paid*, so the clock runs and the bill stays at zero — a seated booking
 *    ends without anyone taking a second payment (§5, bookings.md §2).
 *
 * The second booking is paid from the member's wallet, which is the tender
 * `03-member-redeem` filled and the only route that spends it.
 */
test('a pre-booking is taken, refunded, retaken, checked in and seated', async ({ page }) => {
  const api = await VenueApi.signIn();
  const free = (await api.freeStations('PS5'))[0];
  expect(free, 'a free PS5 to book').toBeTruthy();
  const blockPrice = await api.blockPrice('PS5');
  const settings = await api.get<{ packageFee: number; cancelCutoffHours: number }>(
    '/booking-settings',
  );
  await api.dispose();

  await signIn(page);
  await openScreen(page, '/bookings', 'bookings-screen');

  /* --------------------------------------- taken outside the cutoff, then refunded */

  await page.getByTestId('new-booking').click();
  const form = page.getByTestId('booking-form');
  await expect(form).toBeVisible();

  await chip(page, 'Console', new RegExp(`^${free.name}`)).click();
  await form.getByLabel('Customer name').fill(`E2E Refund ${RUN_ID}`);
  await form.getByLabel('Phone', { exact: true }).fill('01711000777');
  // Well outside the cutoff, so the refund is still open.
  await form.getByTestId('booking-start').fill(venueDateTimeInput(6 * 60));

  // The bill box is a preview of the server's price: blocks × rate + fee.
  const blocks = 2;
  const expectedTotal = blocks * blockPrice + settings.packageFee;
  expect(await amountOf(page, 'booking-total')).toBe(expectedTotal);

  await page.getByTestId('booking-confirm').click();

  const detail = page.getByTestId('booking-detail');
  await expect(detail).toBeVisible();
  await expect(detail).toHaveAttribute('data-status', 'PAID');
  // No drift: what the box promised is what the server charged (§5.11).
  await expect(page.getByTestId('booking-drift-notice')).toHaveCount(0);

  await expect(detail.getByTestId('booking-cancel')).toBeVisible();
  await detail.getByTestId('booking-cancel').click();

  // Refunded: it is off the desk's workload and into History, which is where
  // the rail goes back to idle — Upcoming is what is still owed a console.
  await expect(page.getByTestId('bookings-rail-idle')).toBeVisible();
  await page.getByTestId('bookings-tab-history').click();
  const cancelled = page.getByRole('row', { name: new RegExp(`E2E Refund ${RUN_ID}`) });
  await expect(cancelled).toContainText('Cancelled');
  await cancelled.click();
  await expect(detail).toHaveAttribute('data-status', 'CANCELLED');
  await expect(detail.getByTestId('booking-status')).toContainText('fully refunded');

  await page.getByTestId('bookings-tab-upcoming').click();

  /* ------------------------------- retaken inside the cutoff, out of the wallet */

  // The wallet is topped up first so this scenario stands on its own: a member
  // wallet is a running balance, and a suite that assumed yesterday's would be
  // testing the seed rather than the money path. Cash in, one ledger row, one
  // idempotency key.
  const walletTopUp = 1000;
  await openScreen(page, '/members', 'members-screen');
  await page.getByTestId('member-search-input').fill('Rifat');
  await page.getByRole('row', { name: /Rifat Hasan/ }).click();
  await page.getByTestId('member-rail').getByRole('button', { name: 'Top up' }).click();
  const topUp = page.getByTestId('topup-dialog');
  await topUp.getByLabel('Amount (৳)').fill(String(walletTopUp));
  await topUp.getByRole('button', { name: `Add ৳${walletTopUp.toLocaleString('en-US')}` }).click();
  await expect(topUp).toBeHidden();

  await openScreen(page, '/bookings', 'bookings-screen');
  await page.getByTestId('new-booking').click();
  await expect(form).toBeVisible();

  await chip(page, 'Console', new RegExp(`^${free.name}`)).click();
  // Paid out of that wallet — the one tender no other scenario spends.
  await form.getByLabel('Search name or phone').fill('Rifat');
  await form.getByTestId('member-result').first().click();
  await expect(form.getByLabel('Customer name')).toHaveValue(/Rifat/);

  // Inside the 2 h cutoff: this one cannot be cancelled once taken.
  await form.getByTestId('booking-start').fill(venueDateTimeInput(45));
  await chip(page, 'Paid by', 'Wallet').click();

  const walletTotal = await amountOf(page, 'booking-total');
  await page.getByTestId('booking-confirm').click();

  await expect(detail).toBeVisible();
  await expect(detail).toHaveAttribute('data-status', 'PAID');
  expect(taka(await detail.innerText().then((text) => text.match(/৳[\d,]+/)?.[0] ?? ''))).toBe(
    walletTotal,
  );

  // The cutoff has already passed for this slot: the button is gone and the
  // note that replaces it says why.
  await expect(detail.getByTestId('booking-cancel')).toHaveCount(0);
  await expect(detail.getByTestId('booking-cutoff-note')).toContainText(
    `${settings.cancelCutoffHours} h before the start`,
  );

  /* ------------------------------------------------ check in and take the token */

  await detail.getByTestId('booking-check-in').click();

  // The token comes off a row-locked daily counter, so it is only ever drawn
  // from the response — with the P6 stub the printer produced under it.
  // The token stays on screen — it is read out to the customer — with the P6
  // stub the printer produced under it, because the screen follows the row into
  // History instead of letting the list re-read unmount the rail under it.
  const badge = detail.getByTestId('booking-token');
  await expect(badge).toContainText(/TOKEN #\d{2}/);
  await expect(detail.getByTestId('receipt-render')).toBeVisible();
  await expect(detail).toHaveAttribute('data-status', 'ARRIVED');
  await expect(page.getByTestId('bookings-tab-history')).toHaveAttribute('aria-selected', 'true');

  // Its Status cell in the table is the token now — the number the customer holds.
  await expect(
    page.getByRole('row', { name: /Rifat Hasan.*Token #\d{2}/ }).first(),
  ).toBeVisible();

  /* ------------------------------------------- seated on the Floor, prepaid clock */

  await openScreen(page, '/floor', 'floor-screen');
  await page.getByTestId('station-card').filter({ hasText: free.name }).click();

  const panel = page.getByTestId('session-panel');
  await expect(panel).toHaveAttribute('data-variant', 'seat-prompt');
  // "Seat #04 · Rahim · 1 h prepaid" — the arrival this console was sold to,
  // ahead of any walk-up token of the same type (docs/bookings.md §7).
  const offer = panel.getByTestId('seat-offer').first();
  await expect(offer).toContainText(/^Seat #\d{2} · Rifat Hasan · .* prepaid$/);
  await offer.click();

  // Seated: the booked blocks are loaded **already paid**, so nothing is due on
  // this seat and End is open before a single taka changes hands. The clock
  // itself waits for staff to press start (docs/bookings.md §2).
  await expect(panel).toHaveAttribute('data-variant', 'station');
  await expect(panel).toHaveAttribute('data-state', 'open');
  await expect.poll(async () => taka(await panel.getByTestId('bill-total').innerText())).toBe(0);
  await expect(panel.getByTestId('end-session')).toBeEnabled();
  // Paid time cannot be handed back — 409 `BLOCKS_CONSUMED`, refused up front.
  await expect(panel.getByRole('button', { name: '−30 min block' })).toBeDisabled();

  await panel.getByRole('button', { name: /the clock$/ }).click();
  await expect(panel).toHaveAttribute('data-state', 'running');

  await panel.getByTestId('end-session').click();
  // Free again — "empty", or straight back to a seat prompt when another
  // prepaid token is already waiting for this console.
  await expect(panel).not.toHaveAttribute('data-variant', 'station');
});
