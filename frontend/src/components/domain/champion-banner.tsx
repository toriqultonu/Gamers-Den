/**
 * ChampionBanner — docs/tournaments.md §8: the accent band that replaces the
 * live tiles once the final is decided.
 *
 * The champion is the server's: winning the final sets `winner_entry_id`, turns
 * the event DONE and releases every console it held (§3), so this renders
 * `winnerName` off the tournament rather than reading the last match itself.
 */

import { formatBDT } from '@/lib/money';
import type { Tournament } from '@/features/tournaments/schemas';

export type ChampionBannerProps = {
  tournament: Tournament;
};

export function ChampionBanner({ tournament }: ChampionBannerProps) {
  if (!tournament.winnerName) return null;

  return (
    <div
      data-testid="champion-banner"
      className="flex items-baseline gap-3.5 border-2 border-accent bg-accent px-4.5 py-4 text-on-accent"
    >
      <span className="type-label opacity-85">Champion</span>
      <span className="font-heading text-[28px] leading-none font-extrabold tracking-[-0.03em]">
        {tournament.winnerName}
      </span>
      <span className="ml-auto text-[13px] opacity-85">
        {`wins ${formatBDT(tournament.prizePool ?? 0)}`}
      </span>
    </div>
  );
}
