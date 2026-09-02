/**
 * SegmentedChoice — docs/design.md §2 primitives row. Selected / unselected /
 * disabled options, plus radiogroup keyboard behaviour.
 */

import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { SegmentedChoice } from '@/components/ui';

const OPTIONS = [
  { value: 'dark', label: 'Dark' },
  { value: 'light', label: 'Light' },
  { value: 'auto', label: 'Auto', disabled: true },
] as const;

describe('SegmentedChoice', () => {
  it('renders a radiogroup with the selected option checked', () => {
    render(<SegmentedChoice options={OPTIONS} value="dark" onChange={() => {}} label="Theme" />);
    expect(screen.getByRole('radiogroup', { name: 'Theme' })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: 'Dark' })).toBeChecked();
    expect(screen.getByRole('radio', { name: 'Light' })).not.toBeChecked();
  });

  it('paints the selected option in accent and the rest transparent', () => {
    render(<SegmentedChoice options={OPTIONS} value="dark" onChange={() => {}} label="Theme" />);
    const selected = screen.getByRole('radio', { name: 'Dark' });
    const unselected = screen.getByRole('radio', { name: 'Light' });
    expect(selected).toHaveAttribute('data-state', 'selected');
    expect(selected).toHaveClass('bg-accent', 'text-on-accent');
    expect(unselected).toHaveAttribute('data-state', 'unselected');
    expect(unselected).toHaveClass('bg-transparent');
  });

  it('renders a disabled option at 45% opacity and refuses its click', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(<SegmentedChoice options={OPTIONS} value="dark" onChange={onChange} label="Theme" />);
    const disabled = screen.getByRole('radio', { name: 'Auto' });
    expect(disabled).toBeDisabled();
    expect(disabled).toHaveClass('disabled:opacity-45');
    await user.click(disabled);
    expect(onChange).not.toHaveBeenCalled();
  });

  it('reports the picked value', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(<SegmentedChoice options={OPTIONS} value="dark" onChange={onChange} label="Theme" />);
    await user.click(screen.getByRole('radio', { name: 'Light' }));
    expect(onChange).toHaveBeenCalledWith('light');
  });

  it('moves the selection with the arrow keys, skipping disabled options', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(<SegmentedChoice options={OPTIONS} value="light" onChange={onChange} label="Theme" />);
    await user.tab();
    expect(screen.getByRole('radio', { name: 'Light' })).toHaveFocus();
    await user.keyboard('{ArrowRight}');
    expect(onChange).toHaveBeenLastCalledWith('dark');
    await user.keyboard('{ArrowLeft}');
    expect(onChange).toHaveBeenLastCalledWith('dark');
  });
});
