import { expect, test } from '@playwright/test';
import { amountOf, openScreen, signIn } from './support/app';

/**
 * **counter sale** (frontend/ARCHITECTURE.md §6, second scenario).
 *
 * Someone walks up to the counter, buys a drink and a packet of chips and
 * leaves. No console, no session, no member — the simplest money there is, and
 * the one the till takes most often.
 *
 * The rule under test is the one that surprises people: **loyalty does not
 * apply here.** `POST /payments` carries no `memberId` for a counter cart, so a
 * member can be attached to the bill for the operator's own reference and the
 * points and wallet stay out of it (`PaymentService`, "a counter sale: F&B
 * only, and no member"). Offering a redemption would tender short and earn a
 * 409 `SPLIT_MISMATCH` on every sale — so the panel says so instead.
 */
test('a walk-up buys from the counter and loyalty stays out of it', async ({ page }) => {
  await signIn(page);
  await openScreen(page, '/pos', 'pos-screen');

  const bill = page.getByTestId('bill-panel');
  // Counter is where the POS opens (`features/pos/bill-store.ts`).
  await expect(bill).toHaveAttribute('data-mode', 'counter');
  await expect(bill.getByTestId('bill-empty')).toBeVisible();

  await page.getByTestId('menu-card').filter({ hasText: 'Mineral Water' }).click();
  await page.getByTestId('menu-card').filter({ hasText: 'Pran Chips' }).click();

  await expect(bill.getByTestId('cart-line')).toHaveCount(2);
  const water = 20;
  const chips = 25;
  await expect.poll(() => amountOf(page, 'bill-due')).toBe(water + chips);

  /* --------------------------------------- a member on a bill that cannot use one */

  await bill.getByLabel('Search name or phone').fill('Rifat');
  await bill.getByTestId('member-result').first().click();

  await expect(bill.getByTestId('loyalty-off')).toBeVisible();
  await expect(bill.getByTestId('redeem-stepper')).toHaveCount(0);
  // The wallet chip is offered but not usable without a member on the payment.
  await expect(page.getByTestId('split-method-WALLET')).toBeDisabled();
  // Attaching changed nothing about what is owed.
  expect(await amountOf(page, 'bill-due')).toBe(water + chips);

  /* ------------------------------------------------------------ cash, and a ticket */

  // One cash row for the full amount is the default split: nothing to type.
  await expect(page.getByTestId('split-remainder')).toHaveAttribute('data-balanced', 'true');
  await page.getByTestId('settle').click();

  const receipt = page.getByTestId('receipt-preview');
  await expect(receipt.getByTestId('receipt-render')).toBeVisible();
  await expect(bill.getByTestId('bill-empty')).toBeVisible();
});
