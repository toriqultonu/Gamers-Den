'use client';

/**
 * Member writes: register, top-up, redeem-to-wallet.
 *
 * None of them is optimistic. Registering is cheap to wait for and its refusal
 * is the whole point (`DUPLICATE_PHONE` means this customer is already on
 * file); the two wallet calls move money, and a balance that jumps and then
 * jumps back is how a counter argument starts.
 *
 * Both wallet routes are on the guarded list (`lib/api.ts`,
 * `POST /members/*​/wallet/*`), so they carry an `Idempotency-Key`. The intent
 * names *the figures as well as the member* — "add ৳500 cash to member 7" —
 * because that is what the operator actually meant:
 *
 *  - a second press after a timeout is the same intent, so the same key goes
 *    out and the server replays its stored answer instead of crediting twice;
 *  - editing the amount after a refusal is a *different* intent, so a fresh key
 *    goes out rather than the old key under a new body, which the server would
 *    answer `IDEMPOTENCY_REPLAY`.
 *
 * Every write ends by invalidating `['members']` — the prefix covers both the
 * directory searches and the open member's detail, and the wallet figure the
 * rail prints comes back from the ledger rather than from client arithmetic.
 */

import { useMutation, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { queryKeys } from '@/lib/query-keys';
import type {
  CreateMemberInput,
  Member,
  MemberDetail,
  RedeemPointsInput,
  TopupInput,
} from './schemas';

/** The directory, every open search of it, and the rail. */
function invalidateMembers(client: QueryClient): void {
  void client.invalidateQueries({ queryKey: ['members'] });
}

/**
 * The server's fresh reading, folded into the rail so the wallet figure moves
 * with the response rather than with the refetch behind it. `Member` is a
 * subset of `MemberDetail`, so the visits and bookings already on screen stay.
 */
function mergeIntoDetail(client: QueryClient, member: Member): void {
  if (typeof member.id !== 'number') return;
  client.setQueryData<MemberDetail>(queryKeys.members.detail(member.id), (previous) =>
    previous ? { ...previous, ...member } : previous,
  );
}

/* ------------------------------------------------------------- register */

/**
 * `POST /members` — 409 `DUPLICATE_PHONE`, which S6a renders under the phone
 * field. Not on the idempotency list: registration takes no money, and the
 * duplicate check is itself the guard against a double-tap.
 */
export function useCreateMember() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: CreateMemberInput) => api.post<Member>('/members', input),
    onSuccess: () => invalidateMembers(client),
  });
}

/* --------------------------------------------------------------- wallet */

export type TopupVariables = TopupInput & { memberId: number };

/** `POST /members/{id}/wallet/topup` — money in, one ledger row, one key. */
export function useTopUpWallet() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ memberId, amount, method, paymentRef }: TopupVariables) =>
      api.post<Member>(
        `/members/${memberId}/wallet/topup`,
        { amount, method, paymentRef: paymentRef?.trim() || undefined },
        { intent: `member-topup:${memberId}:${method}:${amount}` },
      ),
    onSuccess: (member) => {
      mergeIntoDetail(client, member);
      invalidateMembers(client);
    },
  });
}

export type RedeemVariables = RedeemPointsInput & { memberId: number };

/**
 * `POST /members/{id}/wallet/redeem-points` — 1 point = ৳1 into the wallet,
 * 409 `INSUFFICIENT_POINTS` past the balance. The stepper caps itself at the
 * points the member was shown to hold, so the 409 is the backstop for a
 * balance that moved underneath the operator, not the everyday message.
 */
export function useRedeemPoints() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ memberId, points }: RedeemVariables) =>
      api.post<Member>(
        `/members/${memberId}/wallet/redeem-points`,
        { points },
        { intent: `member-redeem:${memberId}:${points}` },
      ),
    onSuccess: (member) => {
      mergeIntoDetail(client, member);
      invalidateMembers(client);
    },
  });
}
