/**
 * TokenBadge — docs/design.md §2: variants inline · stub, prop `token`; plus
 * the daily-reset rule from frontend/ARCHITECTURE.md §5.12.
 */

import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { TOKEN_BADGE_VARIANTS, TokenBadge, formatToken } from '@/components/ui';

describe('TokenBadge', () => {
  it.each(TOKEN_BADGE_VARIANTS)('renders the %s variant', (variant) => {
    render(<TokenBadge variant={variant} token={4} />);
    const badge = screen.getByText('TOKEN #04').parentElement;
    expect(badge).toHaveAttribute('data-variant', variant);
  });

  it('pads the token to two digits and keeps it tabular', () => {
    render(<TokenBadge token={7} />);
    const badge = screen.getByText('TOKEN #07').parentElement;
    expect(badge).toHaveClass('tabular');
    expect(formatToken(12)).toBe('#12');
  });

  it('paints the inline variant in accent and the stub on paper', () => {
    const { unmount } = render(<TokenBadge variant="inline" token={4} />);
    expect(screen.getByText('TOKEN #04').parentElement).toHaveClass('bg-accent', 'text-on-accent');
    unmount();

    render(<TokenBadge variant="stub" token={4} />);
    expect(screen.getByText('TOKEN #04').parentElement).toHaveClass('bg-paper', 'type-mono');
  });

  it('shows no date for a token issued today', () => {
    render(<TokenBadge token={4} issuedOn="2026-09-02" today="2026-09-02" />);
    const badge = screen.getByText('TOKEN #04').parentElement;
    expect(badge).not.toHaveAttribute('data-stale');
    expect(screen.queryByText('2026-09-02')).not.toBeInTheDocument();
  });

  it('shows the issue date for a token from a previous day', () => {
    render(<TokenBadge token={4} issuedOn="2026-09-01" today="2026-09-02" />);
    expect(screen.getByText('TOKEN #04').parentElement).toHaveAttribute('data-stale', 'true');
    expect(screen.getByText('2026-09-01')).toBeInTheDocument();
  });
});
