import { API_BASE_URL, BACKEND_ORIGIN, TERMINAL, VenueApi, backendIsUp } from './backend';

/**
 * The pre-flight. Every failure mode a developer meets here is an environment
 * problem, not a bug in a screen, so it is reported once with the command that
 * fixes it rather than as twelve timed-out clicks.
 *
 * What it insists on, and why each one is load-bearing for the money path:
 *
 *  - the backend answers (nothing works without it);
 *  - it has a floor (every scenario seats, bills or blocks a console);
 *  - pre-booking is on (S14 refuses new bookings otherwise);
 *  - a shift is open on this terminal (`POST /payments` has nowhere to post
 *    without one — and `08-shift-close` re-opens the till it closes so a second
 *    run finds the venue as it left it).
 */
async function globalSetup() {
  if (!(await backendIsUp(10_000))) {
    throw new Error(
      [
        `No backend at ${BACKEND_ORIGIN}/actuator/health.`,
        '',
        'Start the seeded dev venue first:',
        '  docker compose -f ../backend/docker-compose.yml up -d',
        '  cd ../backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev',
      ].join('\n'),
    );
  }

  const api = await VenueApi.signIn();
  try {
    const stations = await api.stations();
    if (stations.length === 0) {
      throw new Error(
        'The backend has no stations — this is not the B22 dev seed. Reset it with ' +
          '`npm run e2e:reset`, then start the backend on the `dev` profile.',
      );
    }

    // Two consoles nobody is sitting at. Every scenario seats, books or blocks
    // one, and a run that died half way through leaves them occupied — which is
    // a used-up venue, not a bug in a screen, and is worth saying up front.
    const free = await api.freeStations();
    if (free.length < 2) {
      throw new Error(
        `Only ${free.length} of ${stations.length} consoles are free — a previous run left the ` +
          'floor occupied. Reset the venue with `npm run e2e:reset` and restart the backend.',
      );
    }

    const settings = await api.get<{ enabled?: boolean }>('/booking-settings');
    if (settings.enabled === false) {
      throw new Error(
        'Pre-booking is switched off on this backend, so S14 cannot take a booking. ' +
          'Turn it on in Setup, or reset the seed with `npm run e2e:reset`.',
      );
    }

    // `GET /shifts/current/x-report` is the shift the money path posts to.
    // Its 409/404 is what S7 renders as "no shift is open on this terminal".
    try {
      await api.get('/shifts/current/x-report');
    } catch (error) {
      throw new Error(
        [
          `No shift is open on terminal ${TERMINAL}, so no payment can be taken.`,
          'Reset the dev database (`npm run e2e:reset`) and restart the backend — the',
          'B22 seed opens the till — or open one on S7 by hand before running the suite.',
          `(${(error as Error).message})`,
        ].join('\n'),
      );
    }

    console.log(
      `e2e: seeded venue ready at ${API_BASE_URL} — ${stations.length} consoles ` +
        `(${free.length} free), till open on ${TERMINAL}.`,
    );
  } finally {
    await api.dispose();
  }
}

export default globalSetup;
