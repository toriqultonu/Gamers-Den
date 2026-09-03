/**
 * `NAV[role]` — the one navigation map, and the route guards derived from it.
 *
 * frontend/ARCHITECTURE.md §4.3: "Nav renders from one `NAV[role]` map
 * **filtered by feature flags**". Everything about who-sees-what lives here so
 * the sidebar, the middleware and the shell's access notice cannot disagree —
 * a second hand-written list in a component is how a cashier ends up with a
 * dead Reports link that 403s.
 *
 * Hiding is cosmetic. The API enforces the same matrix (api-contract.md §1)
 * and answers 403 whatever the sidebar drew.
 */

import type { Route } from 'next';

export const ROLES = ['ADMIN', 'MANAGER', 'CASHIER'] as const;
export type Role = (typeof ROLES)[number];

export const ROLE_LABELS: Record<Role, string> = {
  ADMIN: 'Admin',
  MANAGER: 'Manager',
  CASHIER: 'Cashier',
};

/** The one-line role explainer S1 prints under the sign-in button. */
export const ROLE_NOTES: Record<Role, string> = {
  ADMIN: 'Admin — stations, pricing, staff, stock and the pre-booking switches.',
  MANAGER: 'Manager — food, drinks and stock; reports and tournament control.',
  CASHIER: 'Cashier — sessions, the till, members, bookings and play tickets.',
};

export function isRole(value: unknown): value is Role {
  return typeof value === 'string' && (ROLES as readonly string[]).includes(value);
}

/* ---------------------------------------------------------------- the map */

export const NAV_IDS = [
  'overview',
  'floor',
  'bookings',
  'pos',
  'inventory',
  'members',
  'tournaments',
  'shift',
  'expenses',
  'reports',
  'setup',
  'settings',
] as const;
export type NavId = (typeof NAV_IDS)[number];

/**
 * Feature flags the sidebar filters on. Only one so far: `bookings` mirrors
 * `booking_settings.enabled` — off hides S14 everywhere (design.md §1, S14 row).
 */
export type FeatureFlags = {
  bookings: boolean;
};

/** What the terminal assumes before `GET /booking-settings` has answered. */
export const DEFAULT_FEATURE_FLAGS: FeatureFlags = { bookings: false };

export type NavItem = {
  id: NavId;
  /** Sidebar label — not always the screen title (`Bookings` → "Pre-booking"). */
  label: string;
  /** A real route — `typedRoutes` refuses a link this app cannot serve. */
  href: Route;
  /** Hidden while this flag is false. */
  flag?: keyof FeatureFlags;
};

const OVERVIEW: NavItem = { id: 'overview', label: 'Overview', href: '/overview' };
const FLOOR: NavItem = { id: 'floor', label: 'Floor', href: '/floor' };
const BOOKINGS: NavItem = { id: 'bookings', label: 'Bookings', href: '/bookings', flag: 'bookings' };
const POS: NavItem = { id: 'pos', label: 'Point of sale', href: '/pos' };
const INVENTORY: NavItem = { id: 'inventory', label: 'Inventory', href: '/inventory' };
const MEMBERS: NavItem = { id: 'members', label: 'Members', href: '/members' };
const TOURNAMENTS: NavItem = { id: 'tournaments', label: 'Tournaments', href: '/tournaments' };
const SHIFT: NavItem = { id: 'shift', label: 'Shift close', href: '/shift' };
const EXPENSES: NavItem = { id: 'expenses', label: 'Expenses', href: '/expenses' };
const REPORTS: NavItem = { id: 'reports', label: 'Reports', href: '/reports' };
const SETTINGS: NavItem = { id: 'settings', label: 'Settings', href: '/settings' };

/**
 * S10 is one route with two faces (design.md §1): the Admin sees stations,
 * pricing, staff and the pre-booking controls; the Manager sees menu & stock
 * only, and the label says so rather than promising rights they lack.
 */
const SETUP_ADMIN: NavItem = { id: 'setup', label: 'Setup', href: '/setup' };
const SETUP_MANAGER: NavItem = { id: 'setup', label: 'Menu & stock', href: '/setup' };

/** The order is the prototype's; Overview only exists for the owner. */
export const NAV: Record<Role, readonly NavItem[]> = {
  ADMIN: [
    OVERVIEW,
    FLOOR,
    BOOKINGS,
    POS,
    INVENTORY,
    MEMBERS,
    TOURNAMENTS,
    SHIFT,
    EXPENSES,
    REPORTS,
    SETUP_ADMIN,
    SETTINGS,
  ],
  MANAGER: [
    FLOOR,
    BOOKINGS,
    POS,
    INVENTORY,
    MEMBERS,
    TOURNAMENTS,
    SHIFT,
    EXPENSES,
    REPORTS,
    SETUP_MANAGER,
    SETTINGS,
  ],
  CASHIER: [
    FLOOR,
    BOOKINGS,
    POS,
    INVENTORY,
    MEMBERS,
    TOURNAMENTS,
    SHIFT,
    EXPENSES,
    SETTINGS,
  ],
};

/** `NAV[role]` minus the items whose feature flag is off. */
export function visibleNav(role: Role, flags: FeatureFlags = DEFAULT_FEATURE_FLAGS): NavItem[] {
  return NAV[role].filter((item) => !item.flag || flags[item.flag]);
}

/* ------------------------------------------------------------ routes/guards */

/**
 * Every route inside the `(app)` group. `/print` has no nav item — S11 is
 * reached from a settle, a shift close or the job history — but it is still
 * behind the cookie guard.
 */
export const APP_ROUTES: readonly string[] = [
  ...new Set<string>(NAV.ADMIN.map((item) => item.href)),
  '/print',
];

/**
 * The role-gated routes — §4.3: S2 admin, S9 manager+, S10 role-sectioned
 * (the page is Manager+; its booking controls are Admin-only, which S10 itself
 * enforces). Anything absent is open to every signed-in role.
 */
export const ROUTE_ROLES: Record<string, readonly Role[]> = {
  '/overview': ['ADMIN'],
  '/reports': ['ADMIN', 'MANAGER'],
  '/setup': ['ADMIN', 'MANAGER'],
};

/** The `(app)` route a path belongs to, or null for anything outside the shell. */
export function appRouteOf(pathname: string): string | null {
  return (
    APP_ROUTES.find((route) => pathname === route || pathname.startsWith(`${route}/`)) ?? null
  );
}

export function isAppRoute(pathname: string): boolean {
  return appRouteOf(pathname) !== null;
}

/** The roles allowed on a path, or null when it is open to all of them. */
export function routeRoles(pathname: string): readonly Role[] | null {
  const route = appRouteOf(pathname);
  return route ? (ROUTE_ROLES[route] ?? null) : null;
}

export function isRouteAllowed(role: Role, pathname: string): boolean {
  const allowed = routeRoles(pathname);
  return allowed === null || allowed.includes(role);
}

/** Where a role lands after sign-in: the owner on S2, everyone else on S3. */
export function landingPath(role: Role): Route {
  return role === 'ADMIN' ? '/overview' : '/floor';
}

/* ------------------------------------------------------------------ titles */

/**
 * Topbar titles. Mostly the nav label, except S14 — the sidebar says
 * "Bookings", the screen calls itself "Pre-booking" (prototype `titles`).
 */
const TITLE_OVERRIDES: Partial<Record<NavId, string>> = {
  bookings: 'Pre-booking',
};

export function screenTitle(pathname: string, role: Role): string {
  const route = appRouteOf(pathname);
  if (route === '/print') return 'Print preview';
  const item = NAV[role].find((candidate) => candidate.href === route);
  if (!item) return "Gamer's Den";
  return TITLE_OVERRIDES[item.id] ?? item.label;
}
