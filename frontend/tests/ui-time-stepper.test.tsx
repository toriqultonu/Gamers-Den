/**
 * TimeStepper — docs/design.md §2: "−30 disabled at 30 min", props
 * `blocks, onChange`.
 */

import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { TimeStepper, formatBlocks } from '@/components/ui';

describe('TimeStepper', () => {
  it('renders the length and the block count', () => {
    render(<TimeStepper blocks={3} onChange={() => {}} />);
    expect(screen.getByTestId('time-stepper-length')).toHaveTextContent('1 h 30 min');
    expect(screen.getByText('3 × 30 min')).toBeInTheDocument();
  });

  it('disables −30 at 30 min and keeps +30 live', () => {
    render(<TimeStepper blocks={1} onChange={() => {}} />);
    expect(screen.getByRole('button', { name: 'Remove 30 minutes' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Add 30 minutes' })).toBeEnabled();
    expect(screen.getByTestId('time-stepper-length')).toHaveTextContent('30 min');
  });

  it('steps by one block in each direction', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(<TimeStepper blocks={2} onChange={onChange} />);
    await user.click(screen.getByRole('button', { name: 'Add 30 minutes' }));
    expect(onChange).toHaveBeenLastCalledWith(3);
    await user.click(screen.getByRole('button', { name: 'Remove 30 minutes' }));
    expect(onChange).toHaveBeenLastCalledWith(1);
  });

  it('disables +30 at the ceiling', () => {
    render(<TimeStepper blocks={8} onChange={() => {}} max={8} />);
    expect(screen.getByRole('button', { name: 'Add 30 minutes' })).toBeDisabled();
  });

  it('disables both controls when disabled', () => {
    render(<TimeStepper blocks={4} onChange={() => {}} disabled />);
    expect(screen.getByRole('button', { name: 'Remove 30 minutes' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Add 30 minutes' })).toBeDisabled();
  });

  it('formats block lengths the way the booking form reads them', () => {
    expect(formatBlocks(1)).toBe('30 min');
    expect(formatBlocks(2)).toBe('1 h');
    expect(formatBlocks(5)).toBe('2 h 30 min');
  });
});
