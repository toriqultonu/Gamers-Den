import { expect, test } from '@playwright/test';
import { VenueApi } from './support/backend';
import { RUN_ID, amountOf, chip, openScreen, segment, signIn, venueDateTimeInput } from './support/app';

/**
 * **tournament entry sale → auto-bracket at cap → start match → winner →
 * champion** (frontend/ARCHITECTURE.md §6, sixth scenario; docs/tournaments.md).
 *
 * An event is arranged, its seats are sold at the till like anything else, and
 * the bracket draws **itself** the moment the last one goes: the manager never
 * presses "draw" on a full event, because the entry that fills it is the entry
 * that closes registration (`TournamentEntryService.generateIfFull`). That is
 * the assertion this scenario exists for — the bracket is on screen after a
 * sale, not after a click.
 *
 * After that, the two writes that decide the event: **start**, which is
 * ordinary execution any role may do and which puts the match on a blocked
 * console with a clock, and **winner**, which is never optimistic — the click
 * sends and the bracket is redrawn from the server's answer, propagating the
 * winner into the next round until the final produces a champion.
 */
test('a tournament sells out, draws itself, is played and crowns a champion', async ({ page }) => {
  const api = await VenueApi.signIn();
  // Two consoles for the two first-round matches; blocked consoles refuse
  // walk-ins for the length of the event (409 STATION_RESERVED).
  const free = await api.freeStations();
  expect(free.length, 'two consoles to block for the event').toBeGreaterThanOrEqual(2);
  const held = free.slice(0, 2);
  await api.dispose();

  const name = `E2E Cup ${RUN_ID}`;
  const cap = 4;
  const entryFee = 100;
  const players = ['Arif', 'Bipul', 'Chowdhury', 'Delwar'].map((first) => `${first} ${RUN_ID}`);

  await signIn(page);

  /* ------------------------------------------------------------- arrange it (S12) */

  await openScreen(page, '/tournaments', 'tournaments-screen');
  const rail = page.getByTestId('tournament-manager-rail');
  const form = rail.getByTestId('arrange-form');
  await expect(form).toBeVisible();

  await form.getByLabel('Name').fill(name);
  await form.getByLabel('Game', { exact: true }).fill('FIFA 25');
  await form.getByTestId('tournament-when').fill(venueDateTimeInput(60));
  await chip(page, 'Player cap', String(cap)).click();
  await form.getByLabel('Entry fee').fill(String(entryFee));
  await form.getByLabel('Prize pool').fill('500');
  await form.getByLabel('Match', { exact: true }).fill('10');
  for (const station of held) {
    await chip(page, 'Block stations', station.name).click();
  }

  await form.getByTestId('create-tournament').click();

  const card = page.getByTestId('tournament-card').filter({ hasText: name });
  await expect(card).toBeVisible();
  await card.click();
  await expect(page.getByTestId('tournament-heading')).toContainText(name);
  await expect(page.getByTestId('registered-players')).toContainText('No entries sold yet');

  /* ------------------------------------------------ sell the seats at the till (S4) */

  for (const [index, player] of players.entries()) {
    await openScreen(page, '/pos', 'pos-screen');
    await segment(page, 'Bill', 'Counter sale').click();
    await chip(page, 'Menu category', 'Tournament').click();

    const entry = page.getByTestId('menu-card').filter({ hasText: `Entry — ${name}` });
    await expect(entry).toBeEnabled();
    // The card counts down to the last seat, and goes dark on "Full".
    await expect(entry.getByTestId('menu-card-note')).toContainText(`${cap - index} slots left`);
    await entry.click();

    const bill = page.getByTestId('bill-panel');
    // The name on the stub is the name seeded into the bracket (§5).
    await bill.getByTestId('player-name').fill(player);
    await expect.poll(() => amountOf(page, 'bill-due')).toBe(entryFee);

    await page.getByTestId('settle').click();
    await expect(page.getByTestId('entry-tokens')).toBeVisible();
  }

  /* ------------------------------------------- the last sale drew the bracket itself */

  await openScreen(page, '/tournaments', 'tournaments-screen');
  await page.getByTestId('tournament-card').filter({ hasText: name }).click();

  const bracket = page.getByTestId('bracket');
  await expect(bracket).toBeVisible();
  // Nobody pressed "draw": a full event closes its own registration.
  await expect(page.getByTestId('generate-bracket')).toHaveCount(0);
  // N − 1 matches, no byes: 4 players is two semis and a final.
  await expect(page.getByTestId('match-box')).toHaveCount(cap - 1);
  await expect(page.getByTestId('tournament-heading')).toContainText('Live');

  /* ------------------------------------------------------------- play it out */

  const board = page.getByTestId('match-board');
  await expect(board).toBeVisible();
  await board.getByTestId('start-match').first().click();

  // Started: the match is on a console with a clock the whole screen ticks from.
  await expect(page.getByTestId('live-match-tile').first()).toBeVisible();
  await expect(board.getByTestId('start-match').first()).toContainText('In play');

  // Winners, one match at a time, until the bracket has no undecided match
  // left. Each click is a server write and a redraw — the next round's players
  // appear because the server propagated them, not because this screen guessed,
  // which is why the final only becomes decidable once both semis are in.
  for (let decided = 0; decided < cap - 1; decided += 1) {
    const decidable = page.getByRole('button', { name: /^Record .* as the winner$/ }).first();
    await expect(decidable).toBeVisible();
    const recorded = await decidable.getAttribute('aria-label');
    await decidable.click();
    // The row stops being a choice once the server has taken it.
    await expect(page.getByRole('button', { name: recorded ?? '' })).toHaveCount(0);
  }

  /* --------------------------------------------------------------- the champion */

  // A finished event is not "live & upcoming" any more, so the crowning is read
  // where it lasts: History, whose Winner column is the champion's name.
  await segment(page, 'Tournaments', 'History').click();
  const finished = page.getByRole('row', { name: new RegExp(name) });
  await expect(finished).toBeVisible();
  await expect(finished).toContainText(new RegExp(players.join('|')));
});
