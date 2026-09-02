/**
 * Money — integer BDT, everywhere.
 *
 * docs/api-contract.md: "Money in integer BDT". There is no paisa in this
 * system: every amount that crosses the wire is a whole taka, so nothing here
 * rounds, and a fractional input is a bug worth surfacing rather than hiding.
 *
 * The prototype's grouping is Western (`৳392,400`, not `৳3,92,400`) — matching
 * it keeps the bills, the drawer count and the thermal render reading alike.
 */

/** The taka sign the whole UI prints. */
export const CURRENCY_SYMBOL = '৳';

/** ISO code, for the few places that spell it out (exports, sync payloads). */
export const CURRENCY_CODE = 'BDT';

const groupingFormatter = new Intl.NumberFormat('en-US', {
  maximumFractionDigits: 0,
  useGrouping: true,
});

export type FormatMoneyOptions = {
  /**
   * `auto` — a minus sign only when negative (refunds: `−৳150`).
   * `always` — force the sign, for signed columns (`+৳500` / `−৳150`).
   * `never` — magnitude only, when the row's label already carries the sign.
   */
  sign?: 'auto' | 'always' | 'never';
};

/**
 * `৳1,250` — the canonical on-screen amount.
 *
 * Negatives take a real minus (U+2212), not a hyphen, so a refund lines up
 * under a charge in a `tabular-nums` column.
 */
export function formatBDT(amount: number, options: FormatMoneyOptions = {}): string {
  return `${signOf(amount, options.sign)}${CURRENCY_SYMBOL}${formatAmount(amount)}`;
}

/**
 * `1,250` — grouped digits with no symbol, for inputs and for columns that
 * carry the ৳ in their header.
 */
export function formatAmount(amount: number): string {
  if (!Number.isFinite(amount)) return '0';
  return groupingFormatter.format(Math.abs(Math.trunc(amount)));
}

/**
 * Reads an operator-typed amount (`"1,250"`, `"৳1,250"`, `" 500 "`).
 *
 * Returns `null` for anything that is not a whole number of taka — a form shows
 * its own inline error rather than silently charging a rounded amount.
 */
export function parseAmount(input: string): number | null {
  const cleaned = input.replace(/[৳,\s]/g, '').replace(/[−–—]/g, '-');
  if (cleaned === '' || !/^-?\d+$/.test(cleaned)) return null;
  const value = Number(cleaned);
  return Number.isSafeInteger(value) ? value : null;
}

/**
 * The booking form's live bill box: blocks × the console's block rate + the
 * package fee (frontend/ARCHITECTURE.md §5.11).
 *
 * This is a **preview**. The server re-prices at confirm and wins; on drift the
 * rail renders the server total with a notice rather than charging silently.
 */
export function bookingTotal(blocks: number, blockPrice: number, packageFee: number): number {
  return Math.max(0, Math.trunc(blocks)) * Math.trunc(blockPrice) + Math.trunc(packageFee);
}

/** Play time only — the line above the package fee in the same bill box. */
export function playAmount(blocks: number, blockPrice: number): number {
  return bookingTotal(blocks, blockPrice, 0);
}

function signOf(amount: number, mode: FormatMoneyOptions['sign'] = 'auto'): string {
  if (mode === 'never') return '';
  if (amount < 0) return '−';
  return mode === 'always' ? '+' : '';
}
