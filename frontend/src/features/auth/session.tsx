'use client';

/**
 * Who is signed in on this terminal — the session every screen reads and the
 * only place the access token is set.
 *
 * Deliberately not the Zustand store: frontend/ARCHITECTURE.md §4.2 lists what
 * that store holds (selected station, POS mode, bill-draft flags, rail state)
 * and the session is none of it. It is a context because it is read
 * everywhere, written in exactly two places (sign in, sign out) and has to be
 * available above the shell — S1 lives outside `(app)` and still needs it.
 *
 * The access token stays in memory (`lib/api.ts`); the only thing that
 * survives a refresh is the HttpOnly refresh cookie, so a reloaded terminal
 * restores itself by rotating that cookie once on mount rather than by
 * persisting a credential where a script could read it.
 */

import { useRouter } from 'next/navigation';
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import { useQueryClient } from '@tanstack/react-query';
import {
  ApiError,
  api,
  configureApi,
  logout as apiLogout,
  setAccessToken,
  type Schemas,
} from '@/lib/api';
import { isRole, type Role } from '@/lib/nav';
import { clearSessionCookie, readSessionCookie, writeSessionCookie } from '@/lib/session-cookie';
import { TERMINAL_ID } from '@/lib/terminal';
import { rememberStaff } from './staff-roster';

export type SignedInStaff = {
  id: number;
  name: string;
  role: Role;
  avatarColor?: string | null;
};

export type SessionStatus =
  /** Rotating the refresh cookie to see whether this terminal is still signed in. */
  | 'restoring'
  | 'authenticated'
  | 'anonymous';

export type SessionState = {
  status: SessionStatus;
  staff: SignedInStaff | null;
  /** The open shift this login is attached to, if the terminal has one. */
  shiftId: number | null;
  terminal: string;
  /** Auto-lock is showing; the session is intact behind it. */
  locked: boolean;
};

export type SessionContextValue = SessionState & {
  signIn: (input: { staffId: number; pin: string }) => Promise<SignedInStaff>;
  signOut: () => Promise<void>;
  lock: () => void;
  /** Re-checks the PIN of whoever is already signed in. */
  unlock: (pin: string) => Promise<void>;
};

const ANONYMOUS: SessionState = {
  status: 'anonymous',
  staff: null,
  shiftId: null,
  terminal: TERMINAL_ID,
  locked: false,
};

const SessionContext = createContext<SessionContextValue | null>(null);

/** Narrows the loose generated `Staff` into the shape the screens rely on. */
function toSignedInStaff(staff: Schemas['Staff'] | undefined): SignedInStaff | null {
  if (!staff || typeof staff.id !== 'number' || !isRole(staff.role)) return null;
  return {
    id: staff.id,
    name: staff.name ?? `Staff ${staff.id}`,
    role: staff.role,
    avatarColor: staff.avatarColor ?? null,
  };
}

export function SessionProvider({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const queryClient = useQueryClient();
  // A terminal that carries the routing hint was signed in a moment ago, so it
  // starts by restoring rather than by flashing S1 at whoever is standing there.
  const [state, setState] = useState<SessionState>(ANONYMOUS);
  const restored = useRef(false);

  const forget = useCallback(() => {
    clearSessionCookie();
    queryClient.clear();
    setState(ANONYMOUS);
  }, [queryClient]);

  // `lib/api.ts` never navigates on its own; this is the handler it calls when
  // a 401 survives its one silent refresh (§4.4).
  useEffect(() => {
    configureApi({
      onLogout: () => {
        clearSessionCookie();
        setState(ANONYMOUS);
        router.replace('/login');
      },
    });
    return () => configureApi({});
  }, [router]);

  useEffect(() => {
    if (restored.current) return;
    restored.current = true;

    if (!readSessionCookie()) {
      setState(ANONYMOUS);
      return;
    }

    let live = true;
    setState((current) => ({ ...current, status: 'restoring' }));
    void (async () => {
      try {
        const session = await api.post<Schemas['SessionResponse']>('/auth/refresh', undefined, {
          anonymous: true,
        });
        const staff = toSignedInStaff(session.staff);
        if (!live) return;
        if (!staff || !session.accessToken) {
          forget();
          return;
        }
        setAccessToken(session.accessToken);
        writeSessionCookie(staff.role);
        setState({
          status: 'authenticated',
          staff,
          shiftId: session.shiftId ?? null,
          terminal: session.terminal ?? TERMINAL_ID,
          locked: false,
        });
      } catch {
        // An expired or revoked family is an ordinary sign-out, not an error.
        if (live) forget();
      }
    })();

    return () => {
      live = false;
    };
  }, [forget]);

  const applySession = useCallback((session: Schemas['SessionResponse']): SignedInStaff => {
    const staff = toSignedInStaff(session.staff);
    if (!staff || !session.accessToken) {
      throw new ApiError({
        status: 500,
        code: 'UNKNOWN',
        message: 'The server accepted the PIN but sent no session.',
      });
    }
    setAccessToken(session.accessToken);
    writeSessionCookie(staff.role);
    rememberStaff(staff);
    setState({
      status: 'authenticated',
      staff,
      shiftId: session.shiftId ?? null,
      terminal: session.terminal ?? TERMINAL_ID,
      locked: false,
    });
    return staff;
  }, []);

  const signIn = useCallback(
    async ({ staffId, pin }: { staffId: number; pin: string }) => {
      const session = await api.post<Schemas['SessionResponse']>(
        '/auth/login',
        { staffId, pin, terminal: TERMINAL_ID } satisfies Schemas['LoginRequest'],
        { anonymous: true },
      );
      return applySession(session);
    },
    [applySession],
  );

  const signOut = useCallback(async () => {
    await apiLogout();
    forget();
    router.replace('/login');
  }, [forget, router]);

  const lock = useCallback(() => {
    setState((current) => (current.staff ? { ...current, locked: true } : current));
  }, []);

  const unlock = useCallback(
    async (pin: string) => {
      const staff = state.staff;
      if (!staff) return;
      // The PIN check *is* a login — it is the only route that verifies one —
      // so unlocking also refreshes the token the terminal has been idling on.
      const session = await api.post<Schemas['SessionResponse']>(
        '/auth/login',
        { staffId: staff.id, pin, terminal: TERMINAL_ID } satisfies Schemas['LoginRequest'],
        { anonymous: true },
      );
      applySession(session);
    },
    [applySession, state.staff],
  );

  const value = useMemo<SessionContextValue>(
    () => ({ ...state, signIn, signOut, lock, unlock }),
    [state, signIn, signOut, lock, unlock],
  );

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionContextValue {
  const value = useContext(SessionContext);
  if (!value) throw new Error('useSession must be used inside <SessionProvider>');
  return value;
}

/** The session where a signed-in one is guaranteed — inside the `(app)` shell. */
export function useSignedInStaff(): SignedInStaff | null {
  return useSession().staff;
}
