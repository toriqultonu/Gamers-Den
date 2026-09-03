/**
 * `npm run e2e` — build the app the way the venue serves it, then drive it.
 *
 * The build step is not a convenience. `NEXT_PUBLIC_API_BASE_URL` is inlined
 * into the client bundle at build time, and the suite needs the venue's
 * **same-origin** base (`/api/v1`) rather than the developer's cross-origin
 * `http://localhost:8080/api/v1`: the backend disables CORS on purpose
 * (`SecurityConfig`, "a reverse proxy fronts both"), so a browser pointed
 * straight at 8080 is refused before a single screen loads. Next stands in for
 * that proxy through `API_PROXY_TARGET` (next.config.ts), and both variables
 * have to agree — which is what this script is for.
 *
 *   npm run e2e                    # build, then the whole suite
 *   npm run e2e -- 04-booking      # ... one spec
 *   npm run e2e -- --headed        # ... watching it happen
 *   E2E_SKIP_BUILD=1 npm run e2e   # reuse the last e2e build (iterating)
 *
 * Everything after `--` goes to Playwright untouched.
 */

import { spawn } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const RUNNER = path.join(ROOT, 'scripts', 'run.mjs');

/** The backend the built app talks to. Same default as `lib/api.ts`. */
const API_ORIGIN = (process.env.E2E_API_ORIGIN ?? 'http://localhost:8080').replace(/\/+$/, '');

const env = {
  ...process.env,
  // The browser asks its own origin; Next forwards to Spring.
  NEXT_PUBLIC_API_BASE_URL: '/api/v1',
  API_PROXY_TARGET: API_ORIGIN,
  // What `e2e/support/backend.ts` calls directly for readiness and lookups —
  // it is a Node client, not a browser, so it needs the absolute address.
  E2E_API_BASE_URL: `${API_ORIGIN}/api/v1`,
};

function run(args) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [RUNNER, ...args], { cwd: ROOT, stdio: 'inherit', env });
    child.on('exit', (code, signal) => {
      if (code === 0) resolve();
      else reject(Object.assign(new Error(`${args.join(' ')} failed`), { code: code ?? (signal ? 1 : 1) }));
    });
  });
}

try {
  if (process.env.E2E_SKIP_BUILD !== '1') {
    console.log('e2e: building the app with the venue same-origin API base…');
    await run(['next', 'build']);
  }
  await run(['playwright', 'test', ...process.argv.slice(2)]);
} catch (error) {
  process.exit(error.code ?? 1);
}
