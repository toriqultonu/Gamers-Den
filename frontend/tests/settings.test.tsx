/**
 * S13 — Settings (design.md §6, frontend/ARCHITECTURE.md §5.5).
 *
 * State-table assertions, not snapshots. The four rules this screen is not
 * allowed to get wrong:
 *
 *  - **instant, then persisted.** A theme, text-size or accent choice repaints
 *    `<html>` from local state on the click itself — no round trip — and only
 *    then goes to `PUT /terminal-settings` as the whole object.
 *  - **no flash.** The pre-paint script `app/layout.tsx` inlines is really
 *    there, in `<head>`, and really applies the cached appearance — with a
 *    corrupt cache leaving the server-rendered defaults alone.
 *  - **a failed save never destroys the choice.** The notice appears; the app
 *    stays painted the way the operator asked for.
 *  - **the terminal is the owner's.** A cashier's controls are read-only and
 *    fire nothing; their own swatch still writes `PUT /me/prefs`.
 */

import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClientProvider, type QueryClient } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SettingsScreen } from '@/components/domain/settings-screen';
import { SessionProvider } from '@/features/auth/session';
import { makeQueryClient } from '@/lib/query-client';
import { SESSION_COOKIE } from '@/lib/session-cookie';
import { forgetSession, resetIdempotencyKeys } from '@/lib/api';
import { resetServerTime } from '@/lib/time';
import type { Role } from '@/lib/nav';
import {
  accentFromHex,
  appearanceOf,
  applyAppearance,
  noFlashScript,
  readAppearanceCache,
  themeFromApi,
  textSizeFromApi,
} from '@/features/settings/appearance';
import {
  AUTO_LOCK_CHOICES,
  RECEIPT_COPY_CHOICES,
  canEditTerminalSettings,
  settingsDraft,
  settingsDraftSchema,
  toUpdateRequest,
} from '@/features/settings/schemas';
import { APPEARANCE_CACHE_KEY } from '@/styles/tokens';

const NOW = '2026-09-03T14:00:00Z';

vi.mock('next/navigation', () => ({
  useRouter: () => ({
    replace: vi.fn(),
    push: vi.fn(),
    prefetch: vi.fn(),
    refresh: vi.fn(),
    back: vi.fn(),
  }),
  usePathname: () => '/settings',
  useSearchParams: () => new URLSearchParams(),
}));

/* ------------------------------------------------------------- fixtures */

const STAFF_BY_ROLE: Record<Role, { id: number; name: string; role: Role; avatarColor?: string }> = {
  ADMIN: { id: 1, name: 'Rumi Haque', role: 'ADMIN' },
  MANAGER: { id: 2, name: 'Farhan Reza', role: 'MANAGER' },
  CASHIER: { id: 4, name: 'Sabbir Ahmed', role: 'CASHIER' },
};

const SETTINGS = {
  theme: 'DARK',
  fontScale: 'DEFAULT',
  accent: '#ec3013',
  loginBgImageId: null,
  sound: true,
  autoLockMin: 5,
  receiptCopies: 1,
};

/* --------------------------------------------------------------- server */

const fetchMock = vi.fn();

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', Date: new Date(NOW).toUTCString() },
  });
}

type Handlers = {
  putSettings?: (body: Record<string, unknown>) => Response;
  putPrefs?: (body: Record<string, unknown>) => Response;
  uploadBg?: () => Response;
  getSettings?: () => Response;
};

const calls: { method: string; path: string; body: Record<string, unknown>; raw: unknown }[] = [];

/** The terminal row as the mock keeps it: a save moves it, a read returns it. */
let stored: Record<string, unknown> = { ...SETTINGS };
let avatarColor: string | null = null;

function serve(handlers: Handlers = {}, role: Role = 'ADMIN') {
  fetchMock.mockImplementation((input: RequestInfo, init?: RequestInit) => {
    const url = new URL(String(input));
    const path = url.pathname.replace('/api/v1', '');
    const method = (init?.method ?? 'GET').toUpperCase();
    const isForm = typeof FormData !== 'undefined' && init?.body instanceof FormData;
    const raw = init?.body && !isForm ? JSON.parse(String(init.body)) : {};
    const body = (Array.isArray(raw) ? {} : raw) as Record<string, unknown>;
    calls.push({ method, path, body, raw: isForm ? init?.body : raw });

    if (path === '/auth/login' || path === '/auth/refresh') {
      return json({
        accessToken: 'access-token',
        expiresIn: 900,
        staff: STAFF_BY_ROLE[role],
        terminal: 'COUNTER-1',
        tokenType: 'Bearer',
      });
    }

    if (method === 'GET' && path === '/terminal-settings') {
      return handlers.getSettings?.() ?? json(stored);
    }
    if (method === 'PUT' && path === '/terminal-settings') {
      if (handlers.putSettings) return handlers.putSettings(body);
      stored = { ...body };
      return json(stored);
    }
    if (method === 'POST' && path === '/terminal-settings/login-bg') {
      return handlers.uploadBg?.() ?? json({ loginBgImageId: 'img-77' }, 201);
    }

    if (method === 'GET' && path === '/me/prefs') return json({ avatarColor });
    if (method === 'PUT' && path === '/me/prefs') {
      if (handlers.putPrefs) return handlers.putPrefs(body);
      avatarColor = (body.avatarColor as string | null) ?? null;
      return json({ avatarColor });
    }

    return json({});
  });
}

let client: QueryClient;

function renderSettings(role: Role) {
  document.cookie = `${SESSION_COOKIE}=${role}; Path=/`;
  client = makeQueryClient();
  client.setDefaultOptions({ queries: { retry: false } });
  return render(
    <QueryClientProvider client={client}>
      <SessionProvider>
        <SettingsScreen role={role} />
      </SessionProvider>
    </QueryClientProvider>,
  );
}

async function openSettings(role: Role = 'ADMIN') {
  const user = userEvent.setup();
  renderSettings(role);
  await waitFor(() => expect(screen.getByTestId('settings-screen')).toBeInTheDocument());
  await waitFor(() => expect(screen.queryByTestId('settings-skeleton')).not.toBeInTheDocument());
  return user;
}

const requests = (method: string, path: string) =>
  calls.filter((call) => call.method === method && call.path === path);

const radio = (group: string, option: string) =>
  within(screen.getByRole('radiogroup', { name: group })).getByRole('radio', { name: option });

beforeEach(() => {
  calls.length = 0;
  stored = { ...SETTINGS };
  avatarColor = null;
  document.documentElement.dataset.theme = 'dark';
  document.documentElement.dataset.accent = 'red';
  document.documentElement.dataset.textSize = 'default';
  window.localStorage.clear();
  vi.stubGlobal('fetch', fetchMock);
  serve();
});

afterEach(() => {
  client?.clear();
  forgetSession();
  resetIdempotencyKeys();
  resetServerTime();
  vi.unstubAllGlobals();
  fetchMock.mockReset();
  document.cookie = `${SESSION_COOKIE}=; Path=/; Max-Age=0`;
});

/* ------------------------------------------------------- the translation */

describe('the API vocabulary and the token vocabulary', () => {
  it('map both ways, and fall back rather than paint an unknown accent', () => {
    expect(themeFromApi('LIGHT')).toBe('light');
    expect(themeFromApi(undefined)).toBe('dark');
    expect(textSizeFromApi('COMPACT')).toBe('compact');
    expect(accentFromHex('#0F62FE')).toBe('blue');
    // A hex with no tonal ramp behind it would break design.md §3's contrast.
    expect(accentFromHex('#123456')).toBe('red');

    expect(appearanceOf({ theme: 'LIGHT', fontScale: 'LARGE', accent: '#198038' })).toEqual({
      theme: 'light',
      textSize: 'large',
      accent: 'green',
    });
  });

  it('sends the whole object, in the server’s spelling', () => {
    const draft = settingsDraft({ ...SETTINGS, theme: 'LIGHT', fontScale: 'LARGE' });

    expect(toUpdateRequest(draft)).toEqual({
      theme: 'LIGHT',
      fontScale: 'LARGE',
      accent: '#ec3013',
      loginBgImageId: null,
      sound: true,
      autoLockMin: 5,
      receiptCopies: 1,
    });
  });

  it('keeps the closed sets the service validates', () => {
    expect(AUTO_LOCK_CHOICES).toEqual([0, 2, 5, 10]);
    expect(RECEIPT_COPY_CHOICES).toEqual([1, 2]);
    expect(settingsDraftSchema.safeParse(settingsDraft(SETTINGS)).success).toBe(true);
    expect(
      settingsDraftSchema.safeParse({ ...settingsDraft(SETTINGS), autoLockMin: 3 }).success,
    ).toBe(false);
    expect(canEditTerminalSettings('ADMIN')).toBe(true);
    expect(canEditTerminalSettings('MANAGER')).toBe(false);
    expect(canEditTerminalSettings('CASHIER')).toBe(false);
  });
});

/* ------------------------------------------------------ no flash on boot */

describe('theme before first paint (§5.5)', () => {
  it('is inlined in the document head, from the shared builder', () => {
    const layout = readFileSync(resolve(process.cwd(), 'src/app/layout.tsx'), 'utf8');
    const head = layout.slice(layout.indexOf('<head>'), layout.indexOf('</head>'));

    expect(layout).toContain("from '@/features/settings/appearance'");
    expect(layout).toContain('noFlashScript()');
    // In <head>, before the body — otherwise it is not a pre-paint script.
    expect(head).toContain('dangerouslySetInnerHTML');
    expect(head).toContain('NO_FLASH_SCRIPT');
    // The server-rendered html already carries the defaults, so a terminal
    // with JS off still paints dark + Den Red.
    expect(layout).toContain('data-theme={DEFAULT_THEME}');
  });

  it('applies the cached appearance synchronously', () => {
    window.localStorage.setItem(
      APPEARANCE_CACHE_KEY,
      JSON.stringify({ theme: 'light', accent: 'blue', textSize: 'large' }),
    );

    // eslint-disable-next-line no-eval
    (0, eval)(noFlashScript());

    expect(document.documentElement.dataset.theme).toBe('light');
    expect(document.documentElement.dataset.accent).toBe('blue');
    expect(document.documentElement.dataset.textSize).toBe('large');
  });

  it('leaves the server-rendered defaults alone on a corrupt cache', () => {
    window.localStorage.setItem(APPEARANCE_CACHE_KEY, '{not json');

    // eslint-disable-next-line no-eval
    (0, eval)(noFlashScript());

    expect(document.documentElement.dataset.theme).toBe('dark');
    expect(document.documentElement.dataset.accent).toBe('red');
  });

  it('ignores values that are not in the token sets', () => {
    window.localStorage.setItem(
      APPEARANCE_CACHE_KEY,
      JSON.stringify({ theme: 'neon', accent: 'purple', textSize: 'huge' }),
    );

    // eslint-disable-next-line no-eval
    (0, eval)(noFlashScript());

    expect(document.documentElement.dataset.theme).toBe('dark');
    expect(document.documentElement.dataset.accent).toBe('red');
    expect(document.documentElement.dataset.textSize).toBe('default');
  });

  it('applies an appearance to the element it is given', () => {
    const root = document.createElement('div');
    applyAppearance({ theme: 'light', textSize: 'compact', accent: 'green' }, root);

    expect(root.dataset.theme).toBe('light');
    expect(root.dataset.textSize).toBe('compact');
    expect(root.dataset.accent).toBe('green');
  });
});

/* ----------------------------------------------------- instant + persist */

describe('appearance', () => {
  it('repaints on the click and persists the whole object', async () => {
    const user = await openSettings('ADMIN');

    await user.click(radio('Theme', 'Light'));

    // Instant: the attribute moved without waiting for the PUT.
    expect(document.documentElement.dataset.theme).toBe('light');

    await waitFor(() => expect(requests('PUT', '/terminal-settings')).toHaveLength(1));
    expect(requests('PUT', '/terminal-settings')[0]!.body).toEqual({
      theme: 'LIGHT',
      fontScale: 'DEFAULT',
      accent: '#ec3013',
      loginBgImageId: null,
      sound: true,
      autoLockMin: 5,
      receiptCopies: 1,
    });
    expect(await screen.findByTestId('settings-saved')).toBeInTheDocument();
  });

  it('caches the saved appearance for the next first paint', async () => {
    const user = await openSettings('ADMIN');

    await user.click(radio('Text size', 'Large'));

    await waitFor(() => expect(readAppearanceCache().textSize).toBe('large'));
    expect(document.documentElement.dataset.textSize).toBe('large');
    expect(readAppearanceCache().theme).toBe('dark');
  });

  it('carries the accent through as its hex, and repaints with it', async () => {
    const user = await openSettings('ADMIN');

    await user.click(within(screen.getByRole('group', { name: 'Accent colour' })).getByRole('button', { name: 'Blue' }));

    expect(document.documentElement.dataset.accent).toBe('blue');
    await waitFor(() => expect(requests('PUT', '/terminal-settings')).toHaveLength(1));
    expect(requests('PUT', '/terminal-settings')[0]!.body.accent).toBe('#0f62fe');
  });

  it('keeps a second change on top of the first — the draft is the whole object', async () => {
    const user = await openSettings('ADMIN');

    await user.click(radio('Theme', 'Light'));
    await waitFor(() => expect(requests('PUT', '/terminal-settings')).toHaveLength(1));
    await user.click(radio('Auto-lock', '10 min'));

    await waitFor(() => expect(requests('PUT', '/terminal-settings')).toHaveLength(2));
    expect(requests('PUT', '/terminal-settings')[1]!.body).toMatchObject({
      theme: 'LIGHT',
      autoLockMin: 10,
    });
  });

  it('keeps the operator’s choice on screen when the save is refused', async () => {
    serve({
      putSettings: () =>
        json({ error: { code: 'FORBIDDEN', message: 'Admin only', traceId: 't-9' } }, 403),
    });
    const user = await openSettings('ADMIN');

    await user.click(radio('Theme', 'Light'));

    expect(await screen.findByTestId('settings-notice')).toBeInTheDocument();
    // An error never destroys entered data (§4.4): the app stays as asked, and
    // nothing was cached — a reload returns to the server's row.
    expect(document.documentElement.dataset.theme).toBe('light');
    expect(readAppearanceCache().theme).toBeUndefined();
    expect(screen.queryByTestId('settings-saved')).not.toBeInTheDocument();
  });
});

/* ------------------------------------------------------------ the terminal */

describe('terminal behaviour', () => {
  it('saves sound, auto-lock and receipt copies as the same whole object', async () => {
    const user = await openSettings('ADMIN');

    await user.click(radio('Alert and time-up sound', 'Off'));
    await waitFor(() => expect(requests('PUT', '/terminal-settings')).toHaveLength(1));
    expect(requests('PUT', '/terminal-settings')[0]!.body.sound).toBe(false);

    await user.click(radio('Receipt copies', '2'));
    await waitFor(() => expect(requests('PUT', '/terminal-settings')).toHaveLength(2));
    expect(requests('PUT', '/terminal-settings')[1]!.body).toMatchObject({
      sound: false,
      receiptCopies: 2,
    });
  });

  it('uploads a login background, then attaches the id it was given', async () => {
    const user = await openSettings('ADMIN');

    const file = new File(['PNG'], 'venue.png', { type: 'image/png' });
    await user.upload(screen.getByLabelText('Choose image'), file);

    await waitFor(() => expect(requests('POST', '/terminal-settings/login-bg')).toHaveLength(1));
    expect(requests('POST', '/terminal-settings/login-bg')[0]!.raw).toBeInstanceOf(FormData);

    await waitFor(() => expect(requests('PUT', '/terminal-settings')).toHaveLength(1));
    expect(requests('PUT', '/terminal-settings')[0]!.body.loginBgImageId).toBe('img-77');
    // S1 reads the id out of the cache, before anyone has a token.
    await waitFor(() => expect(readAppearanceCache().loginBgImageId).toBe('img-77'));
  });

  it('removes the background by sending null', async () => {
    stored = { ...SETTINGS, loginBgImageId: 'img-1' };
    const user = await openSettings('ADMIN');

    await user.click(screen.getByRole('button', { name: 'Remove' }));

    await waitFor(() => expect(requests('PUT', '/terminal-settings')).toHaveLength(1));
    expect(requests('PUT', '/terminal-settings')[0]!.body.loginBgImageId).toBeNull();
    expect(requests('POST', '/terminal-settings/login-bg')).toHaveLength(0);
  });
});

/* --------------------------------------------------------------- profile */

describe('profile colour', () => {
  it('is written to /me/prefs and moves the sidebar avatar', async () => {
    const user = await openSettings('CASHIER');

    await user.click(screen.getByRole('button', { name: 'Avatar colour #0f62fe' }));

    await waitFor(() => expect(requests('PUT', '/me/prefs')).toHaveLength(1));
    expect(requests('PUT', '/me/prefs')[0]!.body).toEqual({ avatarColor: '#0f62fe' });
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Avatar colour #0f62fe' })).toHaveAttribute(
        'aria-pressed',
        'true',
      ),
    );
  });

  it('resets to the default swatch with null', async () => {
    avatarColor = '#198038';
    const user = await openSettings('ADMIN');

    await waitFor(() => expect(screen.getByRole('button', { name: 'Reset' })).toBeEnabled());
    await user.click(screen.getByRole('button', { name: 'Reset' }));

    await waitFor(() => expect(requests('PUT', '/me/prefs')).toHaveLength(1));
    expect(requests('PUT', '/me/prefs')[0]!.body).toEqual({ avatarColor: null });
  });
});

/* ------------------------------------------------------------ the states */

describe('the state table', () => {
  it('draws a skeleton shaped like the groups while the settings load', async () => {
    let release: (() => void) | null = null;
    serve({
      getSettings: () => json(stored),
    });
    fetchMock.mockImplementation((input: RequestInfo, init?: RequestInit) => {
      const url = new URL(String(input));
      const path = url.pathname.replace('/api/v1', '');
      if (path === '/auth/login' || path === '/auth/refresh') {
        return json({
          accessToken: 'access-token',
          staff: STAFF_BY_ROLE.ADMIN,
          terminal: 'COUNTER-1',
        });
      }
      if (path === '/terminal-settings') {
        return new Promise<Response>((resolve) => {
          release = () => resolve(json(stored));
        });
      }
      return json({});
    });

    renderSettings('ADMIN');

    expect(await screen.findByTestId('settings-skeleton')).toBeInTheDocument();
    release?.();
    await waitFor(() => expect(screen.queryByTestId('settings-skeleton')).not.toBeInTheDocument());
  });

  it('explains a settings read that fails', async () => {
    serve({
      getSettings: () =>
        json({ error: { code: 'UNKNOWN', message: 'boom', traceId: 't-2' } }, 500),
    });
    renderSettings('ADMIN');

    expect(await screen.findByTestId('settings-error')).toBeInTheDocument();
  });

  it('is read-only for a cashier — the controls fire nothing', async () => {
    const user = await openSettings('CASHIER');

    expect(screen.getByTestId('settings-readonly')).toBeInTheDocument();
    expect(radio('Theme', 'Light')).toBeDisabled();
    expect(screen.getByLabelText('Choose image')).toBeDisabled();

    await user.click(radio('Theme', 'Light'));

    expect(requests('PUT', '/terminal-settings')).toHaveLength(0);
    expect(document.documentElement.dataset.theme).toBe('dark');
  });

  it('is read-only for a manager too — the terminal is the owner’s', async () => {
    await openSettings('MANAGER');

    expect(screen.getByTestId('settings-readonly')).toBeInTheDocument();
    expect(radio('Receipt copies', '2')).toBeDisabled();
    // But the swatch is theirs.
    expect(screen.getByRole('button', { name: 'Avatar colour #ec3013' })).toBeEnabled();
  });
});
