import type { Metadata, Viewport } from 'next';
import { Archivo } from 'next/font/google';
import './globals.css';
import { QueryProvider } from '@/lib/query-provider';
import { SessionProvider } from '@/features/auth/session';
import { noFlashScript } from '@/features/settings/appearance';
import { DEFAULT_ACCENT, DEFAULT_TEXT_SIZE, DEFAULT_THEME } from '@/styles/tokens';

const archivo = Archivo({
  subsets: ['latin'],
  weight: ['400', '600', '800'],
  variable: '--font-archivo',
  display: 'swap',
});

export const metadata: Metadata = {
  title: "Gamer's Den",
  description: 'Point-of-sale and floor management for the Gamer’s Den console cafe.',
};

export const viewport: Viewport = {
  width: 'device-width',
  initialScale: 1,
  themeColor: '#171514',
};

/**
 * Theme before first paint (frontend/ARCHITECTURE.md §5.5). The attributes are
 * already correct in the server-rendered HTML, so a terminal with JavaScript
 * disabled still paints dark + Den Red; this script only re-applies whatever
 * the terminal last saved.
 *
 * The script itself is built in `features/settings/appearance.ts`, beside the
 * cache S13 writes and the mapping both of them read — the two must agree on
 * every spelling, so neither gets to keep its own copy (F15).
 */
const NO_FLASH_SCRIPT = noFlashScript();

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html
      lang="en"
      data-theme={DEFAULT_THEME}
      data-accent={DEFAULT_ACCENT}
      data-text-size={DEFAULT_TEXT_SIZE}
      className={archivo.variable}
      suppressHydrationWarning
    >
      <head>
        <script dangerouslySetInnerHTML={{ __html: NO_FLASH_SCRIPT }} />
      </head>
      <body>
        {/* The session sits inside the query cache: signing out clears it. */}
        <QueryProvider>
          <SessionProvider>{children}</SessionProvider>
        </QueryProvider>
      </body>
    </html>
  );
}
