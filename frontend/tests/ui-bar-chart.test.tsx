/**
 * BarChart — docs/design.md §2 primitives row; plain SVG, accent series with an
 * alt-series option, plus the S9 "Not enough data yet" empty state.
 */

import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { BarChart } from '@/components/ui';

const DATA = [
  { label: 'Mon', value: 4200 },
  { label: 'Tue', value: 8400 },
  { label: 'Wed', value: 0 },
];

describe('BarChart', () => {
  it('renders one plain-SVG bar per datum', () => {
    render(<BarChart data={DATA} label="Revenue, last 3 days" />);
    const chart = screen.getByRole('img', { name: 'Revenue, last 3 days' });
    expect(chart.tagName.toLowerCase()).toBe('svg');
    expect(screen.getAllByTestId('bar-chart-bar')).toHaveLength(3);
  });

  it('scales bar heights against the peak value', () => {
    render(<BarChart data={DATA} label="Revenue" height={100} />);
    const [mon, tue, wed] = screen.getAllByTestId('bar-chart-bar');
    expect(Number(tue.getAttribute('height'))).toBe(100);
    expect(Number(mon.getAttribute('height'))).toBeCloseTo(50, 0);
    expect(Number(wed.getAttribute('height'))).toBe(1); // zero still leaves a hairline
  });

  it('paints the second series in bar-alt', () => {
    render(
      <BarChart
        data={[
          { label: 'Sales', value: 10 },
          { label: 'Expenses', value: 6, alt: true },
        ]}
        label="Sales vs expenses"
      />,
    );
    const [first, second] = screen.getAllByTestId('bar-chart-bar');
    expect(first).toHaveAttribute('data-series', 'accent');
    expect(first).toHaveClass('fill-accent');
    expect(second).toHaveAttribute('data-series', 'alt');
    expect(second).toHaveClass('fill-bar-alt');
  });

  it('renders the empty state with no data', () => {
    render(<BarChart data={[]} label="Revenue" />);
    expect(screen.getByTestId('bar-chart-empty')).toHaveTextContent('Not enough data yet');
    expect(screen.queryByRole('img')).not.toBeInTheDocument();
  });

  it('renders the empty state when every value is zero', () => {
    render(<BarChart data={[{ label: 'Mon', value: 0 }]} label="Revenue" />);
    expect(screen.getByTestId('bar-chart-empty')).toBeInTheDocument();
  });
});
