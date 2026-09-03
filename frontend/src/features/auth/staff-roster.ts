/**
 * The staff picker's roster, cached on the terminal.
 *
 * S1 asks the operator to *pick who they are* before typing a PIN
 * (design.md §1, S1), but there is no anonymous roster endpoint to ask:
 * `GET /staff` is Admin-only (api-contract.md §2), and nothing is signed in
 * yet. Inventing a public one is not this task's call — so the counter PC
 * remembers the people who have signed in on it and offers them back. A cold
 * terminal falls through to the staff-id field, which is the same login by
 * hand.
 *
 * It is a convenience list, never an authority: the PIN is still checked by
 * the server, a stale entry simply fails to sign in, and nothing secret is
 * stored — id, name, role and the avatar colour that already renders publicly
 * in the sidebar.
 */

import { isRole, type Role } from '@/lib/nav';

export const STAFF_ROSTER_KEY = 'gd.staff-roster';

/** Newest sign-ins first, and never more than a counter's worth of people. */
const MAX_ENTRIES = 8;

export type RosterEntry = {
  id: number;
  name: string;
  role: Role;
  avatarColor?: string | null;
};

function storage(): Storage | null {
  try {
    return typeof window === 'undefined' ? null : window.localStorage;
  } catch {
    // Private mode / storage disabled — the picker just stays empty.
    return null;
  }
}

function isEntry(value: unknown): value is RosterEntry {
  if (typeof value !== 'object' || value === null) return false;
  const entry = value as Record<string, unknown>;
  return typeof entry.id === 'number' && typeof entry.name === 'string' && isRole(entry.role);
}

export function readRoster(): RosterEntry[] {
  const store = storage();
  if (!store) return [];
  try {
    const parsed: unknown = JSON.parse(store.getItem(STAFF_ROSTER_KEY) ?? '[]');
    return Array.isArray(parsed) ? parsed.filter(isEntry).slice(0, MAX_ENTRIES) : [];
  } catch {
    return [];
  }
}

export function writeRoster(entries: RosterEntry[]): void {
  const store = storage();
  if (!store) return;
  try {
    store.setItem(STAFF_ROSTER_KEY, JSON.stringify(entries.slice(0, MAX_ENTRIES)));
  } catch {
    // A full or read-only store costs the picker, not the login.
  }
}

/** Records a successful sign-in — most recent first, one row per person. */
export function rememberStaff(entry: RosterEntry): RosterEntry[] {
  const next = [entry, ...readRoster().filter((known) => known.id !== entry.id)];
  writeRoster(next);
  return next.slice(0, MAX_ENTRIES);
}

/** Drops one person — a staff member removed in S10 should stop being offered. */
export function forgetStaff(id: number): RosterEntry[] {
  const next = readRoster().filter((known) => known.id !== id);
  writeRoster(next);
  return next;
}

export function clearRoster(): void {
  storage()?.removeItem(STAFF_ROSTER_KEY);
}
