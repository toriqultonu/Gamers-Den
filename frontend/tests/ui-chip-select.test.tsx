/**
 * ChipSelect — docs/design.md §2 primitives row. Single and multiple selection,
 * selected / unselected / disabled chips.
 */

import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { ChipSelect } from '@/components/ui';

const METHODS = [
  { value: 'cash', label: 'Cash' },
  { value: 'bkash', label: 'bKash' },
  { value: 'nagad', label: 'Nagad' },
  { value: 'wallet', label: 'Wallet', disabled: true },
] as const;

describe('ChipSelect (single)', () => {
  it('renders a labelled group of chips', () => {
    render(<ChipSelect options={METHODS} value="cash" onChange={() => {}} label="Paid by" />);
    expect(screen.getByRole('group', { name: 'Paid by' })).toBeInTheDocument();
    expect(screen.getAllByRole('button')).toHaveLength(4);
  });

  it('paints the selected chip in accent with a 2px rule', () => {
    render(<ChipSelect options={METHODS} value="cash" onChange={() => {}} label="Paid by" />);
    const on = screen.getByRole('button', { name: 'Cash' });
    const off = screen.getByRole('button', { name: 'bKash' });
    expect(on).toHaveAttribute('aria-pressed', 'true');
    expect(on).toHaveClass('border-2', 'border-accent', 'bg-accent', 'text-on-accent');
    expect(off).toHaveAttribute('aria-pressed', 'false');
    expect(off).toHaveClass('border-2', 'border-divider', 'bg-transparent');
  });

  it('reports the picked value', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(<ChipSelect options={METHODS} value="cash" onChange={onChange} label="Paid by" />);
    await user.click(screen.getByRole('button', { name: 'Nagad' }));
    expect(onChange).toHaveBeenCalledWith('nagad');
  });

  it('renders a disabled chip at 45% opacity and refuses its click', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(<ChipSelect options={METHODS} value="cash" onChange={onChange} label="Paid by" />);
    const wallet = screen.getByRole('button', { name: 'Wallet' });
    expect(wallet).toBeDisabled();
    expect(wallet).toHaveClass('disabled:opacity-45');
    await user.click(wallet);
    expect(onChange).not.toHaveBeenCalled();
  });

  it('renders nothing selected when the value is null', () => {
    render(<ChipSelect options={METHODS} value={null} onChange={() => {}} label="Paid by" />);
    for (const chip of screen.getAllByRole('button')) {
      expect(chip).toHaveAttribute('aria-pressed', 'false');
    }
  });
});

describe('ChipSelect (multiple)', () => {
  it('marks every selected chip and toggles one off', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(
      <ChipSelect
        multiple
        options={METHODS}
        value={['cash', 'bkash']}
        onChange={onChange}
        label="Split across"
      />,
    );
    expect(screen.getByRole('button', { name: 'Cash' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'bKash' })).toHaveAttribute('aria-pressed', 'true');

    await user.click(screen.getByRole('button', { name: 'Cash' }));
    expect(onChange).toHaveBeenCalledWith(['bkash']);

    await user.click(screen.getByRole('button', { name: 'Nagad' }));
    expect(onChange).toHaveBeenLastCalledWith(['cash', 'bkash', 'nagad']);
  });
});
