'use client';

/**
 * MemberSearch — docs/design.md §2: variants "collapsed, results, attached,
 * auto-attached", state "no-match notice", prop `onAttach`.
 *
 * One box over name and phone, as the API is (`GET /members?q=`). Two things
 * are worth being careful about:
 *
 *  - **auto-attached is a suggestion, not a decision.** A station bill starts
 *    with the session's own member filled in, and the card says so — but the
 *    moment the operator attaches or removes someone by hand, their choice
 *    sticks even if the session disagrees (`memberTouched` in the store).
 *  - **no match is a notice, not an error.** Plenty of customers are not
 *    members; the bill carries on as a walk-in and the notice points at where
 *    to register one.
 */

import { Button } from '@/components/ui/button';
import { FieldInput } from '@/components/ui/field-input';
import { Tag } from '@/components/ui/tag';
import { cn } from '@/components/ui/cn';
import { formatBDT } from '@/lib/money';
import type { Member } from '@/features/pos/queries';

export const MEMBER_SEARCH_VARIANTS = ['collapsed', 'results', 'attached', 'auto-attached'] as const;
export type MemberSearchVariant = (typeof MEMBER_SEARCH_VARIANTS)[number];

export type AttachedMemberView = {
  id: number;
  name: string;
  points: number;
  wallet: number;
  /** Filled in from the session rather than chosen by the operator. */
  auto?: boolean;
};

export type MemberSearchProps = {
  attached: AttachedMemberView | null;
  query: string;
  onQueryChange: (query: string) => void;
  results: Member[];
  /** A search has run and come back with nothing. */
  searching?: boolean;
  onAttach: (member: Member) => void;
  onClear: () => void;
  disabled?: boolean;
  className?: string;
};

/** Which of the four design.md variants is on screen right now. */
export function memberSearchVariant(
  attached: AttachedMemberView | null,
  query: string,
  results: Member[],
): MemberSearchVariant {
  if (attached) return attached.auto ? 'auto-attached' : 'attached';
  return query.trim() !== '' && results.length > 0 ? 'results' : 'collapsed';
}

export function MemberSearch({
  attached,
  query,
  onQueryChange,
  results,
  searching = false,
  onAttach,
  onClear,
  disabled = false,
  className,
}: MemberSearchProps) {
  const variant = memberSearchVariant(attached, query, results);
  const noMatch = !attached && query.trim() !== '' && !searching && results.length === 0;

  return (
    <section
      data-testid="member-search"
      data-variant={variant}
      className={cn('flex flex-col gap-2.5 border-2 border-text bg-bg p-3', className)}
    >
      <h3 className="type-label opacity-55">Member on this bill</h3>

      {attached ? (
        <div className="flex flex-col gap-2.5">
          <div className="flex items-baseline gap-2">
            <div>
              <div className="font-heading text-[16px] font-extrabold">{attached.name}</div>
              {attached.auto ? (
                <Tag variant="outline" data-testid="member-auto">
                  From this session
                </Tag>
              ) : null}
            </div>
            <Button
              variant="ghost"
              size="sm"
              className="ml-auto"
              disabled={disabled}
              onClick={onClear}
            >
              Remove
            </Button>
          </div>
          <dl className="flex gap-4">
            <Figure label="Points" value={String(attached.points)} />
            <Figure label="Worth" value={formatBDT(attached.points)} />
            <Figure label="Wallet" value={formatBDT(attached.wallet)} />
          </dl>
        </div>
      ) : (
        <div className="flex flex-col gap-2">
          <FieldInput
            label="Search name or phone"
            value={query}
            disabled={disabled}
            autoComplete="off"
            onChange={(event) => onQueryChange(event.target.value)}
          />
          {results.length > 0 ? (
            <ul className="flex flex-col gap-1.5">
              {results.map((member) => (
                <li key={member.id}>
                  <button
                    type="button"
                    data-testid="member-result"
                    disabled={disabled}
                    onClick={() => onAttach(member)}
                    className={cn(
                      'flex w-full items-center gap-2.5 border-2 border-divider px-2.5 py-2 text-left',
                      'hover:not-disabled:bg-surface focus-visible:outline-2 focus-visible:outline-accent',
                      'disabled:cursor-not-allowed disabled:opacity-45',
                    )}
                  >
                    <span className="min-w-0">
                      <span className="block font-heading text-[13px] font-extrabold">
                        {member.name}
                      </span>
                      <span className="block text-[11px] opacity-55">{member.phone}</span>
                    </span>
                    <span className="ml-auto text-right">
                      <span className="block text-body tabular">{member.points ?? 0} pts</span>
                      <span className="block text-[11px] opacity-55">
                        {`wallet ${formatBDT(member.wallet ?? 0)}`}
                      </span>
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          ) : null}
          <p
            data-testid={noMatch ? 'member-no-match' : 'member-hint'}
            className={cn('text-[11px]', noMatch ? 'text-accent-strong' : 'opacity-50')}
          >
            {noMatch
              ? 'No member matches — register them from Members, or take payment as a walk-in.'
              : query.trim() !== ''
                ? 'Tap a member to attach them to this bill.'
                : 'Points are worth ৳1 each and can cover part of the bill.'}
          </p>
        </div>
      )}
    </section>
  );
}

function Figure({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="type-label opacity-55">{label}</dt>
      <dd className="font-heading text-[20px] font-extrabold tabular">{value}</dd>
    </div>
  );
}
