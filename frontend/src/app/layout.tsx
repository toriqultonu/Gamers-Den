import type { Metadata, Viewport } from 'next';
import { Archivo } from 'next/font/google';
import './globals.css';
import { QueryProvider } from '@/lib/query-provider';
import { SessionProvider } from '@/features/auth/session';
import {
  APPEARANCE_CACHE_KEY,
  DEFAULT_ACCENT,
  DEFAULT_TEXT_SIZE,
  DEFAULT_THEME,
} from '@/styles/tokens';

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
 */
const NO_FLASH_SCRIPT = `(function(){try{
var s=JSON.parse(localStorage.getItem(${JSON.stringify(APPEARANCE_CACHE_KEY)})||'{}');
var r=document.documentElement;
if(s.theme==='dark'||s.theme==='light')r.dataset.theme=s.theme;
if(s.accent==='red'||s.accent==='blue'||s.accent==='green')r.dataset.accent=s.accent;
if(s.textSize==='compact'||s.textSize==='default'||s.textSize==='large')r.dataset.textSize=s.textSize;
}catch(e){}})();`;

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
