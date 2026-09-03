'use client';

/**
 * The foot of the sidebar: who is signed in, on which terminal, and the way
 * out (design.md §1 shell, prototype "Signed in" block).
 *
 * Sign-out is the one action here that talks to the server — it revokes the
 * refresh family so a walk-away cannot be resumed from the cookie.
 */

import { useState } from 'react';
import { LogOut } from 'lucide-react';
import { AvatarSwatch, Button } from '@/components/ui';
import { ROLE_LABELS } from '@/lib/nav';
import type { SignedInStaff } from '@/features/auth/session';

export function initialsOf(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return '—';
  const first = parts[0]![0] ?? '';
  const last = parts.length > 1 ? (parts[parts.length - 1]![0] ?? '') : '';
  return `${first}${last}`.toUpperCase();
}

export type SignedInCardProps = {
  staff: SignedInStaff;
  terminal: string;
  onSignOut: () => Promise<void> | void;
};

export function SignedInCard({ staff, terminal, onSignOut }: SignedInCardProps) {
  const [leaving, setLeaving] = useState(false);

  const signOut = async () => {
    setLeaving(true);
    try {
      await onSignOut();
    } finally {
      setLeaving(false);
    }
  };

  return (
    <div className="mt-auto flex flex-col gap-3 border-t-2 border-divider px-4 py-4">
      <p className="type-label opacity-55 max-lg:hidden">Signed in</p>
      <div className="flex items-center gap-2.5">
        <AvatarSwatch color={staff.avatarColor} initials={initialsOf(staff.name)} />
        <div className="min-w-0 max-lg:hidden">
          <p className="truncate font-heading text-[13px] font-extrabold">{staff.name}</p>
          <p className="truncate text-[11px] opacity-55">
            {ROLE_LABELS[staff.role]} · {terminal}
          </p>
        </div>
      </div>
      <Button
        variant="secondary"
        loading={leaving}
        onClick={signOut}
        className="justify-start max-lg:justify-center"
      >
        <LogOut aria-hidden="true" className="size-4 shrink-0" strokeWidth={2} />
        <span className="max-lg:hidden">Sign out</span>
      </Button>
    </div>
  );
}
