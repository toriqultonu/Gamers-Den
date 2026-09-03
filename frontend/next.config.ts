import type { NextConfig } from 'next';

/**
 * `API_PROXY_TARGET` — the venue's reverse proxy, in one line.
 *
 * In the venue image the browser, the app and the API share one origin: a proxy
 * fronts Next and Spring together, which is why `lib/api.ts` defaults to a
 * same-origin base and why `SecurityConfig` disables CORS outright. A developer
 * running `next dev` on 3000 and `mvnw spring-boot:run` on 8080 does not have
 * that proxy, and neither does the F17 Playwright run — so when this variable
 * points at the backend, Next itself becomes it.
 *
 *   API_PROXY_TARGET=http://localhost:8080 NEXT_PUBLIC_API_BASE_URL=/api/v1 npm run dev
 *
 * Unset — every ordinary build, including the venue's — it adds no rewrite at
 * all and this file is what it always was.
 */
const proxyTarget = process.env.API_PROXY_TARGET?.replace(/\/+$/, '');

const nextConfig: NextConfig = {
  reactStrictMode: true,
  typedRoutes: true,
  async rewrites() {
    if (!proxyTarget) return [];
    return [{ source: '/api/v1/:path*', destination: `${proxyTarget}/api/v1/:path*` }];
  },
};

export default nextConfig;
