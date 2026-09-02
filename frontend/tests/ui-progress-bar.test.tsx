/**
 * ProgressBar — docs/design.md §2 primitives row: accent and alt series over
 * the `color.track` rail.
 */

import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { PROGRESS_VARIANTS, ProgressBar } from '@/components/ui';

describe('ProgressBar', () => {
  it.each(PROGRESS_VARIANTS)('renders the %s variant', (variant) => {
    render(<ProgressBar variant={variant} value={30} max={60} />);
    const bar = screen.getByRole('progressbar');
    expect(bar).toHaveAttribute('data-variant', variant);
    expect(bar).toHaveClass('bg-track');
    expect(screen.getByTestId('progress-fill')).toHaveClass(
      variant === 'alt' ? 'bg-bar-alt' : 'bg-accent',
    );
  });

  it('reports its value to assistive tech and fills proportionally', () => {
    render(<ProgressBar value={15} max={60} />);
    const bar = screen.getByRole('progressbar');
    expect(bar).toHaveAttribute('aria-valuenow', '15');
    expect(bar).toHaveAttribute('aria-valuemin', '0');
    expect(bar).toHaveAttribute('aria-valuemax', '60');
    expect(screen.getByTestId('progress-fill')).toHaveStyle({ width: '25%' });
  });

  it('clamps out-of-range values', () => {
    const { rerender } = render(<ProgressBar value={-5} max={10} />);
    expect(screen.getByTestId('progress-fill')).toHaveStyle({ width: '0%' });
    rerender(<ProgressBar value={99} max={10} />);
    expect(screen.getByTestId('progress-fill')).toHaveStyle({ width: '100%' });
  });

  it('renders the row label and readout', () => {
    render(<ProgressBar value={4} max={10} label="Mon" valueLabel="৳4,200" />);
    expect(screen.getByText('Mon')).toBeInTheDocument();
    expect(screen.getByText('৳4,200')).toHaveClass('tabular');
  });
});
