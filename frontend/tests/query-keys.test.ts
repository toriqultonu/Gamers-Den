/**
 * Query keys and cache policy — frontend/ARCHITECTURE.md §4.1 lists the exact
 * arrays the SSE handlers write into, so they are asserted literally here.
 */

import { describe, expect, it } from 'vitest';
import { QueryClient } from '@tanstack/react-query';
import { queryKeys } from '@/lib/query-keys';
import { makeQueryClient, shouldRetryQuery } from '@/lib/query-client';
import { ApiError } from '@/lib/api';

describe('the canonical keys', () => {
  it('are exactly the arrays §4.1 names', () => {
    expect(queryKeys.sessions.all()).toEqual(['sessions']);
    expect(queryKeys.sessions.detail(41)).toEqual(['sessions', 41]);
    expect(queryKeys.sessions.bill(41)).toEqual(['sessions', 41, 'bill']);
    expect(queryKeys.stations.all()).toEqual(['stations']);
    expect(queryKeys.items.all()).toEqual(['items']);
    expect(queryKeys.pricing.all()).toEqual(['pricing']);
    expect(queryKeys.staff.all()).toEqual(['staff']);
    expect(queryKeys.members.search('rafi')).toEqual(['members', 'rafi']);
    expect(queryKeys.members.detail(7)).toEqual(['members', 7]);
    expect(queryKeys.bookings.tab('upcoming')).toEqual(['bookings', 'upcoming']);
    expect(queryKeys.bookings.tab('history')).toEqual(['bookings', 'history']);
    expect(queryKeys.bookings.detail(12)).toEqual(['bookings', 12]);
    expect(queryKeys.bookings.settings()).toEqual(['booking-settings']);
    expect(queryKeys.queue.all()).toEqual(['queue']);
    expect(queryKeys.tournaments.all()).toEqual(['tournaments']);
    expect(queryKeys.tournaments.detail(3)).toEqual(['tournaments', 3]);
    expect(queryKeys.tournaments.finance(3)).toEqual(['tournaments', 3, 'finance']);
    expect(queryKeys.tournaments.history()).toEqual(['tournaments', 'history']);
    expect(queryKeys.tournaments.board(3)).toEqual(['tournaments', 3, 'matches']);
    expect(queryKeys.shift.current()).toEqual(['shift', 'current']);
    expect(queryKeys.expenses.all()).toEqual(['expenses']);
    expect(queryKeys.overview.all()).toEqual(['overview']);
    expect(queryKeys.reports.range('14d')).toEqual(['reports', '14d']);
    expect(queryKeys.printJobs.detail(9)).toEqual(['print-jobs', 9]);
    expect(queryKeys.printJobs.render(9)).toEqual(['print-jobs', 9, 'render']);
    expect(queryKeys.printers.all()).toEqual(['printers']);
    expect(queryKeys.terminalSettings.all()).toEqual(['terminal-settings']);
    expect(queryKeys.sync.status()).toEqual(['sync']);
    expect(queryKeys.alerts.all()).toEqual(['alerts']);
  });

  it('nest so an SSE station-update can invalidate one session or all of them', () => {
    const client = new QueryClient();
    client.setQueryData(queryKeys.sessions.detail(41), { id: 41 });
    client.setQueryData(queryKeys.sessions.bill(41), { total: 500 });

    const matches = client.getQueryCache().findAll({ queryKey: queryKeys.sessions.all() });

    expect(matches).toHaveLength(2);
    client.clear();
  });
});

describe('retry policy', () => {
  const conflict = new ApiError({ status: 409, code: 'SESSION_HAS_BALANCE', message: 'balance' });
  const offline = new ApiError({ status: 0, code: 'NETWORK_ERROR', message: 'offline' });
  const boom = new ApiError({ status: 500, code: 'UNKNOWN', message: 'boom' });

  it('never re-asks a question the server already answered', () => {
    expect(shouldRetryQuery(0, conflict)).toBe(false);
    expect(shouldRetryQuery(0, new ApiError({ status: 403, code: 'FORBIDDEN', message: '' }))).toBe(
      false,
    );
  });

  it('retries the venue box being briefly away', () => {
    expect(shouldRetryQuery(0, offline)).toBe(true);
    expect(shouldRetryQuery(1, boom)).toBe(true);
    expect(shouldRetryQuery(2, offline)).toBe(false);
  });

  it('leaves mutations to the operator — money never retries itself', () => {
    const defaults = makeQueryClient().getDefaultOptions();
    expect(defaults.mutations?.retry).toBe(false);
    expect(defaults.queries?.refetchOnReconnect).toBe(true);
  });
});
