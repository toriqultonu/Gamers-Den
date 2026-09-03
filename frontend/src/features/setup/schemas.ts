/**
 * S10 (Setup / Menu & stock) and S5 (Inventory) shapes — docs/design.md §1
 * (S5/S10 rows), docs/bookings.md §1, docs/api-contract.md (Stations, Pricing,
 * Staff, Menu & stock).
 *
 * Two things live here rather than in the screens:
 *
 *  1. **who sees which section.** S10 is one route with two faces — "Admin:
 *     stations, pricing, staff, menu, pre-booking controls. Manager: menu &
 *     stock only" — and that sentence is a pure function of the role, so it is
 *     written once, tested directly, and read by both the screen and the rail.
 *     Hiding stays cosmetic: every write behind it is re-checked by the API,
 *     which answers 403 whatever was drawn (frontend/ARCHITECTURE.md §4.3).
 *  2. **the stock verdict** S5 prints in its Status column and repeats in its
 *     low-stock rail. The server already answers `lowStock` / `outOfStock`;
 *     what it has no opinion on is the middle band — "watch" — so that one
 *     step is derived here from the same two numbers the table shows, and
 *     never re-invented per screen.
 */

import { z } from 'zod';
import type { Schemas } from '@/lib/api';
import { CONSOLE_TYPES, type ConsoleType } from '@/features/queue/schemas';
import type { Role } from '@/lib/nav';

export type Item = Schemas['Item'];
export type Station = Schemas['Station'];
export type Pricing = Schemas['Pricing'];
export type Staff = Schemas['Staff'];

/* ------------------------------------------------------------- role sections */

/**
 * The sections S10 is built from, in the order the prototype stacks them.
 * `printing` is the device card F13 adds — it is terminal housekeeping rather
 * than venue configuration, so it sits with the readers (any Manager+ can test
 * a ticket) while only Admin may move the venue's default.
 */
export const SETUP_SECTIONS = [
  'stations',
  'pricing',
  'prebooking',
  'staff',
  'menu',
  'printing',
] as const;
export type SetupSection = (typeof SETUP_SECTIONS)[number];

const ADMIN_SECTIONS: readonly SetupSection[] = SETUP_SECTIONS;
/** design.md §1, S10 permission-denied row: "Manager sees menu/stock only". */
const MANAGER_SECTIONS: readonly SetupSection[] = ['menu', 'printing'];

/** What this role's S10 renders. A cashier gets nothing — the screen is not theirs. */
export function setupSections(role: Role | null | undefined): readonly SetupSection[] {
  if (role === 'ADMIN') return ADMIN_SECTIONS;
  if (role === 'MANAGER') return MANAGER_SECTIONS;
  return [];
}

export function hasSetupSection(role: Role | null | undefined, section: SetupSection): boolean {
  return setupSections(role).includes(section);
}

/** S10 opens at all for Manager+ only (`ROUTE_ROLES['/setup']`). */
export function canOpenSetup(role: Role | null | undefined): boolean {
  return setupSections(role).length > 0;
}

/**
 * The pre-booking switches are Admin's alone (docs/bookings.md §1: "Feature
 * control (Admin only)") — the one control on this screen whose reach is the
 * whole venue, because turning it off takes the Bookings nav item away from
 * every terminal.
 */
export function canEditBookingSettings(role: Role | null | undefined): boolean {
  return hasSetupSection(role, 'prebooking');
}

/** `PUT /printers/default` is Admin, "as terminal configuration is". */
export function canSetDefaultPrinter(role: Role | null | undefined): boolean {
  return role === 'ADMIN';
}

/** The one-line explainer the rail prints above the forms (prototype `roleNote`). */
export function roleNote(role: Role | null | undefined): string {
  if (role === 'ADMIN') {
    return 'Admin — full control of stations, pricing, staff, stock and the pre-booking switches.';
  }
  if (role === 'MANAGER') {
    return 'Manager — food, drinks and stock. Stations, pricing, staff and pre-booking are the owner’s.';
  }
  return 'Setup is not open to your role.';
}

/* ------------------------------------------------------------------- stock */

/** `items.category` (api-contract.md, Menu & stock). */
export const ITEM_CATEGORIES = ['BEVERAGE', 'FOOD', 'SNACK', 'EXTRAS'] as const;
export const itemCategorySchema = z.enum(ITEM_CATEGORIES);
export type ItemCategory = (typeof ITEM_CATEGORIES)[number];

export const ITEM_CATEGORY_LABELS: Record<ItemCategory, string> = {
  BEVERAGE: 'Beverage',
  FOOD: 'Food',
  SNACK: 'Snack',
  EXTRAS: 'Extras',
};

export function itemCategoryLabel(category: string | undefined): string {
  return ITEM_CATEGORY_LABELS[category as ItemCategory] ?? category ?? '—';
}

/**
 * The Status column of S5. `REORDER` and `OUT` are the server's own verdicts
 * (`lowStock`, `outOfStock`); `WATCH` is the prototype's middle band — stock
 * still above the reorder point but inside 1.6× of it, i.e. the shelf that will
 * need a delivery before the week is out.
 */
export const STOCK_STATES = ['OUT', 'REORDER', 'WATCH', 'HEALTHY'] as const;
export type StockState = (typeof STOCK_STATES)[number];

const WATCH_MULTIPLIER = 1.6;

export function stockState(item: Item): StockState {
  const stock = item.stock ?? 0;
  const reorderAt = item.reorderAt ?? 0;
  if (item.outOfStock || stock <= 0) return 'OUT';
  if (item.lowStock || stock <= reorderAt) return 'REORDER';
  if (reorderAt > 0 && stock <= reorderAt * WATCH_MULTIPLIER) return 'WATCH';
  return 'HEALTHY';
}

export const STOCK_STATE_LABELS: Record<StockState, string> = {
  OUT: 'Out of stock',
  REORDER: 'Reorder',
  WATCH: 'Watch',
  HEALTHY: 'Healthy',
};

/** Tag variants per design.md §2 — accent for anything the counter must act on. */
export const STOCK_STATE_TAGS: Record<StockState, 'accent' | 'outline' | 'neutral'> = {
  OUT: 'accent',
  REORDER: 'accent',
  WATCH: 'outline',
  HEALTHY: 'neutral',
};

/** The rail's list: everything at or under its reorder point, emptiest first. */
export function lowStockItems(items: Item[] | undefined): Item[] {
  return (items ?? [])
    .filter((item) => {
      const state = stockState(item);
      return state === 'OUT' || state === 'REORDER';
    })
    .sort((a, b) => (a.stock ?? 0) - (b.stock ?? 0));
}

/** "3 left, reorder point is 6" — the whole reason the card is on the rail. */
export function lowStockNote(item: Item): string {
  const stock = item.stock ?? 0;
  const reorderAt = item.reorderAt ?? 0;
  const left = stock <= 0 ? 'None left' : `${stock} left`;
  return reorderAt > 0 ? `${left}, reorder point is ${reorderAt}` : left;
}

/** S5 lists the whole menu, retired rows included — it is a stock record. */
export function stockRows(items: Item[] | undefined): Item[] {
  return [...(items ?? [])].sort((a, b) => {
    const category = (a.category ?? '').localeCompare(b.category ?? '');
    return category !== 0 ? category : (a.name ?? '').localeCompare(b.name ?? '');
  });
}

/** The three figures above the table: lines carried, out, needing an order. */
export function stockTotals(items: Item[] | undefined): {
  lines: number;
  units: number;
  reorder: number;
  out: number;
} {
  const rows = items ?? [];
  return {
    lines: rows.length,
    units: rows.reduce((sum, item) => sum + (item.stock ?? 0), 0),
    reorder: rows.filter((item) => stockState(item) === 'REORDER').length,
    out: rows.filter((item) => stockState(item) === 'OUT').length,
  };
}

/* --------------------------------------------------------------- the forms */

export const createStationSchema = z.object({
  name: z.string().trim().min(1, 'Name the console.').max(40),
  consoleType: z.enum(CONSOLE_TYPES),
});
export type CreateStationInput = z.infer<typeof createStationSchema>;

/**
 * Admin adds a Manager or a Cashier — never another Admin. The seeded owner is
 * the only one (`CreateStaffRequest.role` is `MANAGER|CASHIER`), so the chip
 * row offers exactly what the API accepts.
 */
export const STAFF_ROLES = ['MANAGER', 'CASHIER'] as const;
export type StaffRole = (typeof STAFF_ROLES)[number];

export const createStaffSchema = z.object({
  name: z.string().trim().min(1, 'Enter the staff member’s name.').max(60),
  role: z.enum(STAFF_ROLES, { message: 'Pick a role.' }),
  pin: z
    .string()
    .trim()
    .regex(/^[0-9]{4}$/, 'The PIN is four digits.'),
});
export type CreateStaffInput = z.infer<typeof createStaffSchema>;

export const createItemSchema = z.object({
  name: z.string().trim().min(1, 'Name the item.').max(60),
  category: itemCategorySchema,
  price: z.int().nonnegative('Price cannot be negative.').max(100_000),
  stock: z.int().nonnegative('Stock cannot be negative.').max(100_000),
  reorderAt: z.int().nonnegative('The reorder point cannot be negative.').max(100_000),
});
export type CreateItemInput = z.infer<typeof createItemSchema>;

/** The inline row editor — price, counted stock, reorder point. */
export const updateItemSchema = z.object({
  price: z.int().nonnegative().max(100_000),
  stock: z.int().nonnegative().max(100_000),
  reorderAt: z.int().nonnegative().max(100_000),
});
export type UpdateItemInput = z.infer<typeof updateItemSchema>;

/**
 * One console type's rates. `perHour` and `perHalfHour` are both sent because
 * the contract carries both; the app bills in 30-minute blocks, so the half is
 * the number that matters and the hour is what the rate card advertises.
 *
 * OPEN FLAG (design.md §8.3, TASKLIST global rule 9): the morning window is
 * documented as 10:00–14:00 at −25% but the owner has not confirmed the hours.
 * The fields therefore show and save whatever the server holds; nothing here
 * guesses a default.
 */
export const pricingFormSchema = z.object({
  consoleType: z.enum(CONSOLE_TYPES),
  perHour: z.int().nonnegative('Rates cannot be negative.').max(100_000),
  perHalfHour: z.int().nonnegative('Rates cannot be negative.').max(100_000),
  morningDiscountPct: z.int().min(0).max(100, 'A discount is 0–100%.'),
  morningStart: z.string().regex(/^\d{2}:\d{2}$/, 'Use HH:MM.'),
  morningEnd: z.string().regex(/^\d{2}:\d{2}$/, 'Use HH:MM.'),
});
export type PricingFormInput = z.infer<typeof pricingFormSchema>;

/** The rate card as a draft the card edits, one row per console type. */
export function pricingDraft(pricing: Pricing[] | undefined): Record<ConsoleType, PricingFormInput> {
  const rows = pricing ?? [];
  const draft = {} as Record<ConsoleType, PricingFormInput>;
  for (const consoleType of CONSOLE_TYPES) {
    const row = rows.find((rate) => rate.consoleType === consoleType);
    draft[consoleType] = {
      consoleType,
      perHour: row?.perHour ?? 0,
      perHalfHour: row?.perHalfHour ?? 0,
      morningDiscountPct: row?.morningDiscountPct ?? 0,
      morningStart: row?.morningStart ?? '',
      morningEnd: row?.morningEnd ?? '',
    };
  }
  return draft;
}

/** Only the console types whose numbers actually moved get sent. */
export function changedPricing(
  pricing: Pricing[] | undefined,
  draft: Record<ConsoleType, PricingFormInput>,
): PricingFormInput[] {
  const stored = pricingDraft(pricing);
  return CONSOLE_TYPES.filter(
    (consoleType) =>
      JSON.stringify(stored[consoleType]) !== JSON.stringify(draft[consoleType]),
  ).map((consoleType) => draft[consoleType]);
}

/* ---------------------------------------------------------------- helpers */

export const STATION_STATUS_LABELS: Record<string, string> = {
  AVAILABLE: 'Available',
  MAINTENANCE: 'Maintenance',
};

/**
 * Whether removing this station can only 409. `floorState` already says what
 * the floor is doing with it — anything but FREE or MAINTENANCE means a live
 * session, a reserved match or a checked-in arrival is pointing at the row.
 */
export function stationRemovable(station: Station): boolean {
  return station.floorState === 'FREE' || station.floorState === 'MAINTENANCE';
}

export function staffRoleLabel(role: string | undefined): string {
  if (role === 'ADMIN') return 'Admin';
  if (role === 'MANAGER') return 'Manager';
  if (role === 'CASHIER') return 'Cashier';
  return role ?? '—';
}

/**
 * The first message for a field, or nothing. Shared with S8's expense form
 * (`features/shift/schemas.ts`) rather than re-derived — the shape of a Zod
 * issue is not this feature's business.
 */
export { fieldError } from '@/features/shift/schemas';
