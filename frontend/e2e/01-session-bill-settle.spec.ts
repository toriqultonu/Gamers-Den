import { expect, test } from '@playwright/test';
import { VenueApi } from './support/backend';
import { amountOf, openScreen, segment, chip, signIn, taka } from './support/app';

/**
 * **session → blocks → bill → split settle → ticket** (frontend/ARCHITECTURE.md
 * §6, first scenario).
 *
 * The spine of the venue: a walk-in sits down, buys an hour, has a drink, pays
 * part in cash and part on bKash, and gets a ticket. Everything on the way is
 * the real backend — the block price comes off the seeded rate card, the cart
 * is server-side from the first line, and the settle is one `POST /payments`
 * with an `Idempotency-Key`.
 *
 * The two rules being watched are the ones a bug would cost real money:
 *
 *  - **the split has to add up.** The Settle button stays down while the rows
 *    are short, and a bKash row with no TrxID is not tenderable — the client
 *    twins of 409 `SPLIT_MISMATCH` and `PAYMENT_REF_REQUIRED`;
 *  - **a session with a balance cannot be ended.** End is refused until the
 *    money is taken and re-enabled the moment it is, which is
 *    `SESSION_HAS_BALANCE` and the panel saying the same thing.
 */
test('a walk-in session is billed, split-settled and ended', async ({ page }) => {
  const api = await VenueApi.signIn();
  const free = (await api.freeStations('PS5'))[0];
  expect(free, 'the seeded floor should have a free PS5').toBeTruthy();
  // The venue's own rate, not a number copied out of the seed: a rate change in
  // Setup must move this assertion, not break it.
  const blockPrice = await api.blockPrice('PS5');
  await api.dispose();

  await signIn(page);

  /* ---------------------------------------------- the console and the clock */

  await openScreen(page, '/floor', 'floor-screen');
  await page.getByTestId('station-card').filter({ hasText: free.name }).click();

  const panel = page.getByTestId('session-panel');
  await expect(panel).toHaveAttribute('data-variant', 'empty');
  await panel.getByTestId('start-session').click();

  // A session with no time bought yet: design.md's "open" state.
  await expect(panel).toHaveAttribute('data-variant', 'station');
  await expect(panel).toHaveAttribute('data-state', 'open');

  // An hour, one 30-minute block at a time — the only optimistic write on S3.
  await panel.getByRole('button', { name: '+30 min block' }).click();
  await expect(panel).toContainText('1 × 30 min bought');
  await panel.getByRole('button', { name: '+30 min block' }).click();
  await expect(panel).toContainText('2 × 30 min bought');

  // The running bill is the server's (`GET /sessions/{id}/bill`); only the
  // block count moves optimistically, so this waits for the re-read.
  const gaming = blockPrice * 2;
  await expect
    .poll(() => amountOf(page, 'bill-total'), { message: 'two PS5 blocks at the rate card' })
    .toBe(gaming);

  // "Start" or "Resume" — the panel names the button from the session's own
  // `startedAt`, and a walk-in that has already had time bought reads as resumed.
  await panel.getByRole('button', { name: /the clock$/ }).click();
  await expect(panel).toHaveAttribute('data-state', 'running');

  // The balance is why End is refused (`SESSION_HAS_BALANCE`).
  await expect(panel.getByTestId('end-session')).toBeDisabled();
  await expect(panel.getByTestId('end-blocked-note')).toBeVisible();

  /* --------------------------------------------------------------- the bill */

  await panel.getByTestId('bill-link').click();
  await openScreen(page, '/pos', 'pos-screen');

  await segment(page, 'Bill', /^Station/).click();
  await chip(page, 'Console', free.name).click();
  const bill = page.getByTestId('bill-panel');
  await expect(bill).toHaveAttribute('data-mode', 'station');
  await expect(bill.getByTestId('bill-gaming')).toContainText('1 h');

  // A drink on the same bill — the cart is server-side from this click.
  await page.getByTestId('menu-card').filter({ hasText: 'Coca-Cola 250ml' }).click();
  await expect(bill.getByTestId('cart-line')).toContainText('Coca-Cola 250ml');

  const fnb = 30; // the seeded price
  const due = await amountOf(page, 'bill-due');
  expect(due).toBe(gaming + fnb);

  /* ------------------------------------------------------- cash + bKash, 409s */

  const split = page.getByTestId('payment-split');
  const remainder = page.getByTestId('split-remainder');
  await split.getByTestId('split-method-BKASH').click();

  // Cash cut back with nothing yet on bKash: the bill is short, the remainder
  // line says by how much, and Settle is down — 409 `SPLIT_MISMATCH`, refused
  // before it can be earned.
  const cash = 100;
  await split.getByLabel('Cash', { exact: true }).fill(String(cash));
  await expect(remainder).not.toHaveAttribute('data-balanced', 'true');
  expect(taka(await remainder.innerText())).toBe(due - cash);
  await expect(page.getByTestId('settle')).toBeDisabled();

  // Balanced, but a manual MFS payment with no TrxID is still not tenderable —
  // 409 `PAYMENT_REF_REQUIRED`, said under the field it belongs to.
  await split.getByLabel('bKash', { exact: true }).fill(String(due - cash));
  await expect(remainder).toHaveAttribute('data-balanced', 'true');
  await expect(page.getByTestId('settle')).toBeDisabled();

  await split.getByTestId('split-ref-BKASH').fill(`TRX${Date.now()}`);
  await expect(page.getByTestId('settle')).toBeEnabled();

  /* ------------------------------------------------------ settle and the ticket */

  await page.getByTestId('settle').click();

  // The server's stored render, read back from the print job the settle queued
  // — never a receipt drawn here (frontend/ARCHITECTURE.md §5.6).
  const receipt = page.getByTestId('receipt-preview');
  await expect(receipt).toBeVisible();
  await expect(receipt.getByTestId('receipt-render')).toBeVisible();
  await expect(receipt.getByTestId('print-job-status')).toBeVisible();

  // The draft is gone and the bill is empty — but only now, after the response.
  await expect(page.getByTestId('bill-empty')).toBeVisible();

  /* ------------------------------------------------------------ end the session */

  await openScreen(page, '/floor', 'floor-screen');
  await page.getByTestId('station-card').filter({ hasText: free.name }).click();
  await expect(panel).toHaveAttribute('data-variant', 'station');
  expect(taka(await panel.getByTestId('bill-total').innerText())).toBe(0);

  await expect(panel.getByTestId('end-session')).toBeEnabled();
  await panel.getByTestId('end-session').click();

  await expect(panel).toHaveAttribute('data-variant', 'empty');
});
