import { request, type APIRequestContext } from '@playwright/test';

/**
 * The seeded backend, talked to directly.
 *
 * Used for two things only, and deliberately not for a third:
 *
 *  1. **readiness** — `global-setup.ts` asks whether the venue is up and
 *     seeded before a single browser starts;
 *  2. **reading state a scenario needs to choose its target** — which console
 *     is free, which member exists — facts the operator can see on screen and
 *     the test would otherwise scrape out of the DOM to act on.
 *
 * It is never used to *write* money. Every payment, booking, seat, entry,
 * winner and shift close in this suite goes through the UI, because that is
 * what F17 is for.
 */

/** Same default as `lib/api.ts`, and it must match the build under test. */
export const API_BASE_URL =
  process.env.E2E_API_BASE_URL ??
  process.env.NEXT_PUBLIC_API_BASE_URL ??
  'http://localhost:8080/api/v1';

/** `http://localhost:8080` — actuator lives beside the API, not inside it. */
export const BACKEND_ORIGIN = API_BASE_URL.replace(/\/api\/v1\/?$/, '');

/** The demo staff the B22 seed plants, PIN 1234 for all three. */
export const STAFF = {
  admin: { id: 1, name: 'Admin', pin: '1234' },
} as const;

/** The terminal this browser signs in as — `lib/terminal.ts`'s default. */
export const TERMINAL = process.env.NEXT_PUBLIC_TERMINAL_ID?.trim() || 'T1';

/** `/stations` -> `http://localhost:8080/api/v1/stations`. */
function url(path: string): string {
  return `${API_BASE_URL}${path}`;
}

export type Station = {
  id: number;
  name: string;
  consoleType: 'PS5' | 'PS4';
  status?: string;
  session?: { id?: number; memberId?: number | null } | null;
  match?: unknown;
};

export type Pricing = {
  consoleType: 'PS5' | 'PS4';
  perHalfHour: number;
  perHour: number;
  /** The morning discount applied, when we are inside the window. */
  currentBlockPrice?: number;
};

export type Member = {
  id: number;
  name: string;
  points: number;
  wallet: number;
};

export type QueueEntry = {
  id: number;
  tokenNo: number;
  tokenDate?: string;
  status: string;
  consoleType: string;
  playerName?: string;
};

/**
 * An authenticated API client for the seeded venue.
 *
 * The refresh cookie rides in the context's own jar, so the caller gets a
 * client that keeps working for the length of a spec without knowing that.
 */
export class VenueApi {
  private constructor(
    private readonly http: APIRequestContext,
    private readonly token: string,
  ) {}

  static async signIn(staffId: number = STAFF.admin.id, pin: string = STAFF.admin.pin) {
    // No `baseURL`: Playwright resolves a leading-slash path against the
    // *origin*, which would drop the `/api/v1` prefix and 401 on every call.
    const http = await request.newContext();
    const response = await http.post(url('/auth/login'), {
      data: { staffId, pin, terminal: TERMINAL },
    });
    if (!response.ok()) {
      throw new Error(
        `POST ${API_BASE_URL}/auth/login answered ${response.status()} for staff ${staffId}. ` +
          `Is this the dev-seeded database? ${await response.text()}`,
      );
    }
    const body = (await response.json()) as { accessToken?: string };
    if (!body.accessToken) throw new Error('login answered 200 with no access token');
    return new VenueApi(http, body.accessToken);
  }

  async get<T>(path: string): Promise<T> {
    const response = await this.http.get(url(path), {
      headers: { Authorization: `Bearer ${this.token}` },
    });
    if (!response.ok()) {
      throw new Error(`GET ${path} answered ${response.status()}: ${await response.text()}`);
    }
    return (await response.json()) as T;
  }

  stations() {
    return this.get<Station[]>('/stations');
  }

  queue() {
    return this.get<QueueEntry[]>('/play-queue');
  }

  /** One member by the name the seed gave them — `GET /members?q=` is paged. */
  async member(name: string) {
    const page = await this.get<{ items?: Member[]; content?: Member[] }>(
      `/members?q=${encodeURIComponent(name)}`,
    );
    const found = (page.items ?? page.content ?? []).find((row) => row.name?.includes(name));
    if (!found) throw new Error(`no member matching "${name}" — is this the dev seed?`);
    return found;
  }

  /** The rate card, so a scenario asserts the venue's own prices, not a guess. */
  pricing() {
    return this.get<Pricing[]>('/pricing');
  }

  /** What one 30-minute block costs on this console type right now. */
  async blockPrice(consoleType: 'PS5' | 'PS4'): Promise<number> {
    const row = (await this.pricing()).find((rate) => rate.consoleType === consoleType);
    if (!row) throw new Error(`no rate card row for ${consoleType}`);
    return row.currentBlockPrice ?? row.perHalfHour;
  }

  /** Consoles of a type with no session and nothing reserving them. */
  async freeStations(consoleType?: 'PS5' | 'PS4') {
    const stations = await this.stations();
    return stations.filter(
      (station) =>
        !station.session &&
        !station.match &&
        station.status !== 'MAINTENANCE' &&
        (consoleType === undefined || station.consoleType === consoleType),
    );
  }

  dispose() {
    return this.http.dispose();
  }
}

/** True once `/actuator/health` answers UP, or false after `timeoutMs`. */
export async function backendIsUp(timeoutMs = 5_000): Promise<boolean> {
  const http = await request.newContext({ timeout: timeoutMs });
  try {
    const response = await http.get(`${BACKEND_ORIGIN}/actuator/health`);
    return response.ok();
  } catch {
    return false;
  } finally {
    await http.dispose();
  }
}
