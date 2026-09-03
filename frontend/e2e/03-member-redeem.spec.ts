import { expect, test } from '@playwright/test';
import { VenueApi } from './support/backend';
import { amountOf, chip, openScreen, segment, signIn, taka } from './support/app';

/**
 * **member redeem** (frontend/ARCHITECTURE.md §6, third scenario).
 *
 * Points are worth ৳1 and are spent two different ways, so both are here:
 *
 *  1. **converted into wallet money** on S9 — `POST /members/{id}/wallet/redeem-points`
 *     moves the balance across, and the wallet it fills is what pays for the
 *     pre-booking in `04-booking`;
 *  2. **redeemed against a bill** on S4 — a discount inside the settle, capped
 *     at `min(points, subtotal)`, which is where the stepper's top rung comes
 *     from.
 *
 * The bill is the seeded member's own session, and that is the point rather
 * than convenience: **loyalty follows the session, not the panel.** The server
 * reads the member off `sessions.member_id` — a redemption or a wallet tender on
 * a walk-in is refused with "a WALLET tender needs a member on the session" —
 * so a bill that offers a redemption has to be a bill someone is seated on.
 */
test('a member converts points to wallet, then redeems the rest against their bill', async ({
  page,
}) => {
  const api = await VenueApi.signIn();
  const seated = (await api.stations()).find((station) => station.session?.memberId);
  expect(seated, 'the seed puts a member on a running session').toBeTruthy();
  // Points are spent, not renewed: a venue a previous run already redeemed
  // against has nothing left to discount, and that is an exhausted fixture
  // rather than a broken screen. Say so here instead of timing out on a rung
  // the stepper is right not to offer.
  const seatedMember = await api.get<{ points: number }>(
    `/members/${seated!.session!.memberId}`,
  );
  const walker = await api.member('Rifat');
  await api.dispose();

  const exhausted = 'reset the venue with `npm run e2e:reset` and restart the backend';
  expect(walker.points, `no points left to convert — ${exhausted}`).toBeGreaterThan(0);
  expect(seatedMember.points, `no points left to redeem — ${exhausted}`).toBeGreaterThan(0);

  await signIn(page);

  /* ------------------------------------------------- S9: points into the wallet */

  await openScreen(page, '/members', 'members-screen');
  await page.getByTestId('member-search-input').fill('Rifat');
  await page.getByRole('row', { name: /Rifat Hasan/ }).click();

  const rail = page.getByTestId('member-rail');
  await expect(rail).toBeVisible();
  const walletBefore = taka(await rail.getByTestId('member-wallet').innerText());

  await rail.getByTestId('open-redeem').click();
  const dialog = page.getByTestId('redeem-dialog');
  await expect(dialog).toBeVisible();

  // The whole balance, off the stepper's own top rung — the cap is the member's
  // points and the rungs above it are not rendered, so a balance that a
  // previous run has already spent down is still convertible, just smaller.
  const converted = Number(await dialog.getByTestId('redeem-stepper').getAttribute('data-max'));
  expect(converted, 'the seeded member has points to convert').toBeGreaterThan(0);
  await chip(page, 'Redeem points', `Max ${converted}`).click();
  await dialog.getByRole('button', { name: `Redeem ${converted} pts` }).click();

  await expect(dialog).toBeHidden();
  await expect
    .poll(async () => taka(await rail.getByTestId('member-wallet').innerText()))
    .toBe(walletBefore + converted);

  /* ------------------------------------- S4: the other kind of redemption, on a bill */

  await openScreen(page, '/pos', 'pos-screen');
  await segment(page, 'Bill', /^Station/).click();
  await chip(page, 'Console', seated!.name).click();

  const bill = page.getByTestId('bill-panel');
  await expect(bill).toHaveAttribute('data-mode', 'station');
  // The session's own member arrives with the bill — nobody typed a name.
  await expect(bill.getByTestId('member-auto')).toBeVisible();

  const before = await amountOf(page, 'bill-due');
  expect(before).toBeGreaterThan(0);

  const stepper = bill.getByTestId('redeem-stepper');
  const maxRedeem = Number(await stepper.getAttribute('data-max'));
  expect(maxRedeem).toBeGreaterThan(0);
  await chip(page, 'Redeem points', `Max ${maxRedeem}`).click();

  // A redemption is a discount on this bill: ৳1 off per point, no more.
  await expect.poll(() => amountOf(page, 'bill-due')).toBe(before - maxRedeem);
  await expect(bill.getByTestId('bill-redeem')).toContainText(`${maxRedeem} pts`);

  await expect(page.getByTestId('split-remainder')).toHaveAttribute('data-balanced', 'true');
  await page.getByTestId('settle').click();

  await expect(page.getByTestId('receipt-render')).toBeVisible();
  // Settled: the blocks that were owed are paid, so nothing is due on the seat.
  await expect.poll(() => amountOf(page, 'bill-due')).toBe(0);
});
