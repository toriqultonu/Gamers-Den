/**
 * `NAV[role]` and the guards derived from it — frontend/ARCHITECTURE.md §4.3,
 * and the permission matrix in docs/api-contract.md §1.
 *
 * These are the assertions that keep a cashier from being shown a Reports link
 * that 403s, and keep S14 out of the sidebar while pre-booking is switched off.
 */

import { describe, expect, it } from 'vitest';
import {
  NAV,
  ROLES,
  appRouteOf,
  isAppRoute,
  isRouteAllowed,
  isRole,
  landingPath,
  routeRoles,
  screenTitle,
  visibleNav,
  type Role,
} from '@/lib/nav';

const FLAGS_ON = { bookings: true };
const FLAGS_OFF = { bookings: false };

const idsFor = (role: Role, flags = FLAGS_ON) => visibleNav(role, flags).map((item) => item.id);

describe('NAV[role]', () => {
  it('gives the owner every screen, Overview included', () => {
    expect(idsFor('ADMIN')).toEqual([
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
    ]);
  });

  it('gives the manager everything but Overview', () => {
    expect(idsFor('MANAGER')).toEqual([
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
    ]);
    expect(idsFor('MANAGER')).not.toContain('overview');
  });

  it('gives the cashier the floor screens only — no Overview, Reports or Setup', () => {
    expect(idsFor('CASHIER')).toEqual([
      'floor',
      'bookings',
      'pos',
      'inventory',
      'members',
      'tournaments',
      'shift',
      'expenses',
      'settings',
    ]);
    for (const hidden of ['overview', 'reports', 'setup']) {
      expect(idsFor('CASHIER')).not.toContain(hidden);
    }
  });

  it('labels S10 for what the role may actually do there', () => {
    const admin = NAV.ADMIN.find((item) => item.id === 'setup');
    const manager = NAV.MANAGER.find((item) => item.id === 'setup');
    expect(admin?.label).toBe('Setup');
    expect(manager?.label).toBe('Menu & stock');
    // Same screen either way — S10 sections itself by role.
    expect(admin?.href).toBe(manager?.href);
  });

  it('points every item at a route the app can serve', () => {
    for (const role of ROLES) {
      for (const item of NAV[role]) {
        expect(isAppRoute(item.href)).toBe(true);
      }
    }
  });

  it('leaves S12 in place for all three roles (writes are Manager+, the screen is not)', () => {
    for (const role of ROLES) {
      expect(idsFor(role)).toContain('tournaments');
    }
  });
});

describe('the pre-booking feature flag', () => {
  it('hides Bookings for every role when booking_settings.enabled is false', () => {
    for (const role of ROLES) {
      expect(idsFor(role, FLAGS_OFF)).not.toContain('bookings');
    }
  });

  it('shows Bookings for every role when it is on', () => {
    for (const role of ROLES) {
      expect(idsFor(role, FLAGS_ON)).toContain('bookings');
    }
  });

  it('takes away only that item — the rest of the sidebar is untouched', () => {
    for (const role of ROLES) {
      const off = idsFor(role, FLAGS_OFF);
      const on = idsFor(role, FLAGS_ON);
      expect(on.filter((id) => id !== 'bookings')).toEqual(off);
    }
  });

  it('defaults to hidden, so a slow flag read never flashes a screen that may be off', () => {
    expect(visibleNav('ADMIN').map((item) => item.id)).not.toContain('bookings');
  });
});

describe('route guards', () => {
  it('keeps S2 to the owner', () => {
    expect(routeRoles('/overview')).toEqual(['ADMIN']);
    expect(isRouteAllowed('ADMIN', '/overview')).toBe(true);
    expect(isRouteAllowed('MANAGER', '/overview')).toBe(false);
    expect(isRouteAllowed('CASHIER', '/overview')).toBe(false);
  });

  it('keeps S9 to manager and up', () => {
    expect(isRouteAllowed('ADMIN', '/reports')).toBe(true);
    expect(isRouteAllowed('MANAGER', '/reports')).toBe(true);
    expect(isRouteAllowed('CASHIER', '/reports')).toBe(false);
  });

  it('keeps S10 to manager and up (its Admin-only sections are S10s own business)', () => {
    expect(isRouteAllowed('MANAGER', '/setup')).toBe(true);
    expect(isRouteAllowed('CASHIER', '/setup')).toBe(false);
  });

  it('leaves the shared screens open to everyone signed in', () => {
    for (const path of ['/floor', '/pos', '/bookings', '/members', '/shift', '/settings']) {
      expect(routeRoles(path)).toBeNull();
      for (const role of ROLES) expect(isRouteAllowed(role, path)).toBe(true);
    }
  });

  it('guards nested paths by their screen — S11 has no nav item but is still inside', () => {
    expect(appRouteOf('/print/42')).toBe('/print');
    expect(isAppRoute('/print/42')).toBe(true);
    expect(isRouteAllowed('CASHIER', '/print/42')).toBe(true);
  });

  it('treats anything outside the shell as none of its business', () => {
    expect(isAppRoute('/login')).toBe(false);
    expect(isAppRoute('/tokens')).toBe(false);
    expect(appRouteOf('/floorplan')).toBeNull();
  });
});

describe('landing screens and titles', () => {
  it('lands the owner on S2 and everyone else on S3', () => {
    expect(landingPath('ADMIN')).toBe('/overview');
    expect(landingPath('MANAGER')).toBe('/floor');
    expect(landingPath('CASHIER')).toBe('/floor');
  });

  it('titles the screen, which is not always the nav label', () => {
    expect(screenTitle('/floor', 'CASHIER')).toBe('Floor');
    expect(screenTitle('/bookings', 'CASHIER')).toBe('Pre-booking');
    expect(screenTitle('/setup', 'ADMIN')).toBe('Setup');
    expect(screenTitle('/setup', 'MANAGER')).toBe('Menu & stock');
    expect(screenTitle('/print/42', 'CASHIER')).toBe('Print preview');
  });
});

describe('isRole', () => {
  it('accepts the three roles and nothing else', () => {
    for (const role of ROLES) expect(isRole(role)).toBe(true);
    for (const junk of ['admin', 'OWNER', '', null, undefined, 1]) {
      expect(isRole(junk)).toBe(false);
    }
  });
});
