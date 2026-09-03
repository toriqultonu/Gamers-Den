/**
 * Member shapes — docs/api-contract.md (Members, wallet, points), backend
 * `member/web/*Request.java`.
 *
 * Three writes live behind S6 and S6a, and each one has a refusal the screen
 * has to render rather than swallow:
 *
 *  - **register** — 409 `DUPLICATE_PHONE`, because the server compares phones
 *    normalised (separators dropped), so the same customer typed two ways is
 *    one member, not two;
 *  - **top-up** — money in, so `Idempotency-Key` and a whole number of taka;
 *  - **redeem** — 409 `INSUFFICIENT_POINTS` past the balance. 1 point = ৳1,
 *    into the wallet.
 *
 * The client checks what it can before the call (a name, a phone, a positive
 * amount, a redemption inside the balance it was shown) so the common mistakes
 * are inline errors instead of round trips — but the server's answer is the
 * one that decides, and every refusal above is rendered where it happened with
 * the form intact (frontend/ARCHITECTURE.md §4.4).
 */

import { z } from 'zod';
import type { Schemas } from '@/lib/api';

export type Member = Schemas['Member'];
export type MemberDetail = Schemas['MemberDetail'];
export type MemberVisit = Schemas['MemberVisit'];
export type MemberBooking = Schemas['MemberBooking'];
export type PageResponseMember = Schemas['PageResponseMember'];

/** What the desk seats them on by default (`CreateMemberRequest.preferredConsole`). */
export const PREFERRED_CONSOLES = ['PS5', 'PS4'] as const;
export type PreferredConsole = (typeof PREFERRED_CONSOLES)[number];

/**
 * `TopupRequest.method` — "a wallet cannot fund itself, so no WALLET"
 * (backend `TopupMethod`). The tender list is otherwise the settle's.
 */
export const TOPUP_METHODS = ['CASH', 'BKASH', 'NAGAD'] as const;
export type TopupMethod = (typeof TOPUP_METHODS)[number];

export const TOPUP_METHOD_LABELS: Record<TopupMethod, string> = {
  CASH: 'Cash',
  BKASH: 'bKash',
  NAGAD: 'Nagad',
};

/** The two tenders that carry a TrxID worth writing down. */
export function isMfs(method: TopupMethod): boolean {
  return method === 'BKASH' || method === 'NAGAD';
}

/* ------------------------------------------------------------- register */

/**
 * `POST /members`. Lengths are the server's (`@Size(max = 80)` / `32`), so a
 * name too long for the column is an inline error rather than a 400.
 */
export const createMemberSchema = z.object({
  name: z.string().trim().min(1, 'Enter the member’s name.').max(80),
  phone: z.string().trim().min(1, 'Enter a phone number.').max(32),
  preferredConsole: z.enum(PREFERRED_CONSOLES).optional(),
  games: z.array(z.string().trim().min(1)).optional(),
});

export type CreateMemberInput = z.infer<typeof createMemberSchema>;
export type CreateMemberRequest = Schemas['CreateMemberRequest'];

/* ------------------------------------------------------------- the wallet */

/** `POST /members/{id}/wallet/topup` — integer BDT, ≥ 1, and how it came in. */
export const topupSchema = z.object({
  amount: z.int().min(1, 'Enter an amount to add.').max(1_000_000),
  method: z.enum(TOPUP_METHODS),
  paymentRef: z.string().trim().max(64).optional(),
});

export type TopupInput = z.infer<typeof topupSchema>;

/** `POST /members/{id}/wallet/redeem-points` — 1 pt = ৳1 into the wallet. */
export const redeemPointsSchema = z.object({
  points: z.int().min(1, 'Choose how many points to convert.'),
});

export type RedeemPointsInput = z.infer<typeof redeemPointsSchema>;

/**
 * The ceiling the stepper offers. Redeem-to-wallet has no bill to cap against
 * — unlike the settle discount, which is capped at min(points, total) — so the
 * only limit is what the member holds, and going past it is
 * `INSUFFICIENT_POINTS`.
 */
export function maxRedeemablePoints(member: Pick<Member, 'points'> | undefined): number {
  return Math.max(0, Math.trunc(member?.points ?? 0));
}

/* ------------------------------------------------------------ the reading */

/** The first inline error for a field, from a `safeParse` failure. */
export function fieldError(
  error: z.ZodError<unknown> | null | undefined,
  field: string,
): string | undefined {
  if (!error) return undefined;
  return error.issues.find((issue) => issue.path[0] === field)?.message;
}

/**
 * The rail's kicker: "Registered today" for someone who walked in an hour ago,
 * "Member since Jan 2025" for everyone else. Dated from the venue's own day,
 * not the terminal's — the clock is the server's (§5.2).
 */
export function memberSince(createdAt: string | undefined, today: string): string {
  if (!createdAt) return 'Member';
  if (createdAt.slice(0, 10) === today) return 'Registered today';
  const when = new Date(createdAt);
  if (Number.isNaN(when.getTime())) return 'Member';
  return `Member since ${when.toLocaleDateString('en-GB', { month: 'short', year: 'numeric' })}`;
}

/** "PS5 · FIFA 25, Tekken 8" — the "Plays" column and the rail's sub-line. */
export function playsSummary(member: Pick<Member, 'preferredConsole' | 'games'>): string {
  const parts = [member.preferredConsole, (member.games ?? []).join(', ')].filter(
    (part): part is string => typeof part === 'string' && part.trim() !== '',
  );
  return parts.length > 0 ? parts.join(' · ') : '—';
}
