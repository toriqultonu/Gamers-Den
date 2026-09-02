/**
 * StatTile — docs/design.md §2 primitives row: default and accent tiles.
 */

import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { STAT_TILE_VARIANTS, StatTile } from '@/components/ui';

describe('StatTile', () => {
  it.each(STAT_TILE_VARIANTS)('renders the %s variant', (variant) => {
    render(<StatTile variant={variant} label="Revenue today" value="৳9,110" hint="38 sessions" />);
    const tile = screen.getByText('Revenue today').parentElement;
    expect(tile).toHaveAttribute('data-variant', variant);
    expect(screen.getByText('৳9,110')).toBeInTheDocument();
    expect(screen.getByText('38 sessions')).toBeInTheDocument();
  });

  it('fills the accent tile and inks it with on-accent', () => {
    render(<StatTile variant="accent" label="Net profit today" value="৳7,930" />);
    const tile = screen.getByText('Net profit today').parentElement;
    expect(tile).toHaveClass('bg-accent', 'text-on-accent');
  });

  it('renders the figure tabular so tiles line up', () => {
    render(<StatTile label="Occupancy now" value="63%" />);
    expect(screen.getByText('63%')).toHaveClass('tabular');
  });

  it('renders without a hint', () => {
    render(<StatTile label="Avg. ticket" value="৳386" />);
    expect(screen.getByText('৳386')).toBeInTheDocument();
  });
});
