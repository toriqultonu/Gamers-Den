'use client';

/**
 * The sidebar — brand block, `NAV[role]`, and the signed-in card at the foot.
 *
 * The item list comes from `lib/nav.ts` and nowhere else, already filtered by
 * the feature flags (frontend/ARCHITECTURE.md §4.3). Between 768 and 1023 the
 * rail narrows to icons (design.md §4); below 768 the app shows the
 * larger-screen notice instead, so there is no phone layout to answer for.
 */

import Link from 'next/link';
import {
  BarChart3,
  Boxes,
  CalendarClock,
  ClipboardList,
  LayoutGrid,
  type LucideIcon,
  Monitor,
  Receipt,
  Settings as SettingsIcon,
  ShoppingCart,
  Sliders,
  Trophy,
  Users,
} from 'lucide-react';
import { cn } from '@/components/ui';
import type { NavId, NavItem } from '@/lib/nav';

/** design.md §7 — Lucide only, one glyph per screen. */
const ICONS: Record<NavId, LucideIcon> = {
  overview: LayoutGrid,
  floor: Monitor,
  bookings: CalendarClock,
  pos: ShoppingCart,
  inventory: Boxes,
  members: Users,
  tournaments: Trophy,
  shift: ClipboardList,
  expenses: Receipt,
  reports: BarChart3,
  setup: Sliders,
  settings: SettingsIcon,
};

export type SidebarNavProps = {
  items: readonly NavItem[];
  /** The `(app)` route currently rendered — `appRouteOf(pathname)`. */
  activeRoute: string | null;
  /** Per-item marks: the LIVE mark on Tournaments, counts elsewhere. */
  badges?: Partial<Record<NavId, string>>;
};

export function SidebarNav({ items, activeRoute, badges = {} }: SidebarNavProps) {
  return (
    <nav aria-label="Main" className="flex flex-col py-2">
      {items.map((item) => {
        const Icon = ICONS[item.id];
        const active = item.href === activeRoute;
        const badge = badges[item.id];
        return (
          <Link
            key={item.id}
            href={item.href}
            data-nav-id={item.id}
            data-active={active || undefined}
            aria-current={active ? 'page' : undefined}
            title={item.label}
            className={cn(
              'relative flex items-center gap-3 py-2.5 pr-3 pl-4 text-body',
              'focus-visible:outline-2 focus-visible:outline-accent focus-visible:-outline-offset-2',
              active
                ? 'bg-accent-tint font-heading font-extrabold text-text'
                : 'text-text opacity-75 hover:opacity-100 hover:bg-neutral-100',
            )}
          >
            <span
              aria-hidden="true"
              className={cn('absolute inset-y-0 left-0 w-[3px]', active ? 'bg-accent' : 'bg-transparent')}
            />
            <Icon aria-hidden="true" className="size-4 shrink-0" strokeWidth={2} />
            <span className="min-w-0 truncate max-lg:hidden">{item.label}</span>
            {badge ? (
              <span
                className={cn(
                  'ml-auto shrink-0 px-1.5 py-0.5 text-[10px] tracking-label uppercase max-lg:hidden',
                  'bg-accent text-on-accent',
                )}
              >
                {badge}
              </span>
            ) : null}
          </Link>
        );
      })}
    </nav>
  );
}
