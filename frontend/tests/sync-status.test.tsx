/**
 * The sync chip — `['sync']` → *synced / syncing / offline since HH:MM*
 * (design.md §1 "Global"; frontend/ARCHITECTURE.md §5.8).
 *
 * The chip is about the cloud mirror, not the venue: offline is a note, not an
 * error, because the terminal is talking to the box in the same room and every
 * screen still works. What is worth asserting is that it never claims a clean
 * mirror it has not confirmed, and that "since" is venue time.
 */

import { act, render, screen } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SyncChip } from '@/components/domain/sync-chip';
import { TopBar } from '@/components/domain/top-bar';
import { syncChipState, useSyncStatus } from '@/features/sync/use-sync-status';
import { applyLiveEvent } from '@/lib/sse';
import { makeQueryClient } from '@/lib/query-client';
import { queryKeys } from '@/lib/query-keys';
import { forgetSession } from '@/lib/api';

/** 2026-09-03 08:30 UTC — 14:30 in Asia/Dhaka, which is what the chip must say. */
const LAST_SYNCED = '2026-09-03T08:30:00Z';

const fetchMock = vi.fn();

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function Chip() {
  const sync = useSyncStatus();
  return <SyncChip state={sync.state} lastSyncedAt={sync.lastSyncedAt} />;
}

function renderChip() {
  return render(
    <QueryClientProvider client={makeQueryClient()}>
      <Chip />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock);
  fetchMock.mockReset();
  forgetSession();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('the chip states', () => {
  it('renders all three', () => {
    const { rerender } = render(<SyncChip state="synced" />);
    expect(screen.getByTestId('sync-chip')).toHaveAttribute('data-state', 'synced');
    expect(screen.getByTestId('sync-chip')).toHaveTextContent('Synced');

    rerender(<SyncChip state="syncing" />);
    expect(screen.getByTestId('sync-chip')).toHaveAttribute('data-state', 'syncing');
    expect(screen.getByTestId('sync-chip')).toHaveTextContent('Syncing');

    rerender(<SyncChip state="offline" lastSyncedAt={LAST_SYNCED} />);
    expect(screen.getByTestId('sync-chip')).toHaveAttribute('data-state', 'offline');
    expect(screen.getByTestId('sync-chip')).toHaveTextContent('Offline since 14:30');
  });

  it('says plain "Offline" when the cloud has never been reached', () => {
    render(<SyncChip state="offline" />);
    expect(screen.getByTestId('sync-chip')).toHaveTextContent('Offline');
    expect(screen.getByTestId('sync-chip')).not.toHaveTextContent('since');
  });
});

describe('server state → chip state', () => {
  it('maps the three the backend sends', () => {
    expect(syncChipState({ state: 'SYNCED' })).toBe('synced');
    expect(syncChipState({ state: 'SYNCING', pendingOps: 12 })).toBe('syncing');
    expect(syncChipState({ state: 'OFFLINE', lastSyncedAt: LAST_SYNCED })).toBe('offline');
  });

  it('never claims a clean mirror it has not confirmed', () => {
    expect(syncChipState(undefined)).toBe('syncing');
    expect(syncChipState({ state: 'SOMETHING_NEW' })).toBe('syncing');
  });

  it('calls an unanswering venue box offline', () => {
    expect(syncChipState({ state: 'SYNCED' }, { unreachable: true })).toBe('offline');
  });
});

describe('the chip on screen', () => {
  it('reads GET /sync/status and shows offline since venue time', async () => {
    fetchMock.mockResolvedValue(
      json({ state: 'OFFLINE', lastSyncedAt: LAST_SYNCED, pendingOps: 12 }),
    );
    renderChip();

    const chip = await screen.findByTestId('sync-chip');
    await vi.waitFor(() => expect(chip).toHaveAttribute('data-state', 'offline'));
    expect(chip).toHaveTextContent('Offline since 14:30');
  });

  it('goes offline when the read itself fails', async () => {
    fetchMock.mockRejectedValue(new TypeError('venue box unreachable'));
    // Straight to the failure: the two transport retries are the subject of
    // query-keys.test.ts, and waiting out their backoff proves nothing here.
    const cache = makeQueryClient();
    cache.setDefaultOptions({ queries: { retry: false } });
    render(
      <QueryClientProvider client={cache}>
        <Chip />
      </QueryClientProvider>,
    );

    const chip = await screen.findByTestId('sync-chip');
    await vi.waitFor(() => expect(chip).toHaveAttribute('data-state', 'offline'));
  });

  it('follows an SSE sync-status straight into the chip', async () => {
    fetchMock.mockResolvedValue(json({ state: 'SYNCED', lastSyncedAt: LAST_SYNCED, pendingOps: 0 }));
    const cache = makeQueryClient();
    render(
      <QueryClientProvider client={cache}>
        <Chip />
      </QueryClientProvider>,
    );

    const chip = await screen.findByTestId('sync-chip');
    await vi.waitFor(() => expect(chip).toHaveAttribute('data-state', 'synced'));

    act(() =>
      applyLiveEvent(cache, 'sync-status', {
        state: 'SYNCING',
        lastSyncedAt: LAST_SYNCED,
        pendingOps: 4,
      }),
    );

    await vi.waitFor(() => expect(chip).toHaveAttribute('data-state', 'syncing'));
    expect(cache.getQueryData(queryKeys.sync.status())).toMatchObject({ pendingOps: 4 });
  });

  it('rides in the topbar, beside the occupancy and the clock', () => {
    render(
      <TopBar
        title="Floor"
        occupancy={{ busy: 2, total: 4 }}
        sync={{ state: 'offline', lastSyncedAt: LAST_SYNCED, pendingOps: 12 }}
      />,
    );

    expect(screen.getByTestId('sync-chip')).toHaveTextContent('Offline since 14:30');
    expect(screen.getByText('2/4')).toBeInTheDocument();
  });
});
