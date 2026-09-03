/**
 * Which POS terminal this browser is.
 *
 * `POST /auth/login` takes a `terminal` (api-contract.md §2) and the JWT
 * carries it as a claim, so shifts, prints and idempotency all hang off it.
 * There is one counter PC per venue, configured at image time; the env var is
 * the knob, and `T1` is what the single-terminal venue runs on.
 */

export const DEFAULT_TERMINAL_ID = 'T1';

export const TERMINAL_ID = process.env.NEXT_PUBLIC_TERMINAL_ID?.trim() || DEFAULT_TERMINAL_ID;

/** The S1 kicker: "FRONT DESK TERMINAL T1". */
export function terminalLabel(terminal: string = TERMINAL_ID): string {
  return `Front desk terminal ${terminal}`;
}
