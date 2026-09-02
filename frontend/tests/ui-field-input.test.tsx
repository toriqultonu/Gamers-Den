/**
 * FieldInput — docs/design.md §2 primitives row. Default, hint, error and
 * disabled states; typing survives an error (ARCHITECTURE §4.4).
 */

import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { FieldInput } from '@/components/ui';

describe('FieldInput', () => {
  it('renders a labelled control in its default state', async () => {
    const user = userEvent.setup();
    render(<FieldInput label="Customer name" placeholder="e.g. Rakib Hossain" />);
    const input = screen.getByLabelText('Customer name');
    expect(input).toBeEnabled();
    expect(input).not.toHaveAttribute('aria-invalid');
    await user.type(input, 'Rakib');
    expect(input).toHaveValue('Rakib');
  });

  it('renders a hint under the control', () => {
    render(<FieldInput label="Phone" hint="Bangladesh numbers only" />);
    const input = screen.getByLabelText('Phone');
    expect(input).toHaveAccessibleDescription('Bangladesh numbers only');
  });

  it('renders the error state without destroying what was typed', () => {
    render(<FieldInput label="Phone" defaultValue="+880 17" error="Phone looks incomplete" />);
    const input = screen.getByLabelText('Phone');
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(input).toHaveClass('border-accent');
    expect(input).toHaveValue('+880 17');
    expect(screen.getByRole('alert')).toHaveTextContent('Phone looks incomplete');
  });

  it('lets the error replace the hint', () => {
    render(<FieldInput label="Phone" hint="Bangladesh numbers only" error="Required" />);
    expect(screen.queryByText('Bangladesh numbers only')).not.toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('Required');
  });

  it('renders disabled at 45% opacity', () => {
    render(<FieldInput label="Member" disabled />);
    const input = screen.getByLabelText('Member');
    expect(input).toBeDisabled();
    expect(input).toHaveClass('disabled:opacity-45');
  });

  it('renders a trailing suffix', () => {
    render(<FieldInput label="Float" suffix="৳" />);
    expect(screen.getByText('৳')).toBeInTheDocument();
  });
});
