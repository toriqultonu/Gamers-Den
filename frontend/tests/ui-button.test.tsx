/**
 * Button — docs/design.md §2 row:
 *   variants primary · secondary · ghost · icon · block
 *   states   default · hover · active · focus-visible · disabled · loading
 *
 * Hover/active are pure CSS pseudo-classes, so they are asserted as the classes
 * that carry them rather than by faking a pointer in jsdom.
 */

import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { BUTTON_VARIANTS, Button, type ButtonVariant } from '@/components/ui';

describe('Button variants', () => {
  it.each(BUTTON_VARIANTS)('renders the %s variant', (variant) => {
    render(<Button variant={variant}>Start session</Button>);
    const button = screen.getByRole('button', { name: 'Start session' });
    expect(button).toBeInTheDocument();
    expect(button).toHaveAttribute('data-variant', variant);
    expect(button).toHaveAttribute('type', 'button');
  });

  const FILLS: Record<ButtonVariant, string> = {
    primary: 'bg-accent',
    secondary: 'border-divider',
    ghost: 'text-accent-strong',
    icon: 'border-divider',
    block: 'w-full',
  };

  it.each(BUTTON_VARIANTS)('paints the %s variant from the tokens', (variant) => {
    render(<Button variant={variant}>Go</Button>);
    expect(screen.getByRole('button')).toHaveClass(FILLS[variant]);
  });

  it('centres the label on the full-width block variant', () => {
    render(<Button variant="block">Confirm & take payment</Button>);
    const button = screen.getByRole('button');
    expect(button).toHaveClass('w-full');
    expect(button).toHaveClass('justify-center');
  });

  it('gives the icon variant a square box and no padding', () => {
    render(
      <Button variant="icon" aria-label="Add block">
        +
      </Button>,
    );
    expect(screen.getByRole('button', { name: 'Add block' })).toHaveClass('size-9');
  });
});

describe('Button states', () => {
  it('is enabled and idle by default', () => {
    render(<Button>Pause</Button>);
    const button = screen.getByRole('button');
    expect(button).toBeEnabled();
    expect(button).not.toHaveAttribute('aria-busy');
    expect(screen.queryByTestId('button-spinner')).not.toBeInTheDocument();
  });

  it('carries hover and active steps one rung down the ramp', () => {
    render(<Button variant="primary">Settle</Button>);
    const button = screen.getByRole('button');
    expect(button).toHaveClass('hover:not-disabled:bg-accent-600');
    expect(button).toHaveClass('active:not-disabled:bg-accent-700');
  });

  it('draws a 2px accent focus-visible outline when focused', async () => {
    const user = userEvent.setup();
    render(<Button>End session</Button>);
    const button = screen.getByRole('button');
    await user.tab();
    expect(button).toHaveFocus();
    expect(button).toHaveClass('focus-visible:outline-2');
    expect(button).toHaveClass('focus-visible:outline-accent');
  });

  it('renders disabled at 45% opacity and ignores clicks', async () => {
    const user = userEvent.setup();
    const onClick = vi.fn();
    render(
      <Button disabled onClick={onClick}>
        End session
      </Button>,
    );
    const button = screen.getByRole('button');
    expect(button).toBeDisabled();
    expect(button).toHaveClass('disabled:opacity-45');
    await user.click(button);
    expect(onClick).not.toHaveBeenCalled();
  });

  it('renders loading as busy, spinning, keeping its label and refusing clicks', async () => {
    const user = userEvent.setup();
    const onClick = vi.fn();
    render(
      <Button loading onClick={onClick}>
        Printing…
      </Button>,
    );
    const button = screen.getByRole('button', { name: 'Printing…' });
    expect(button).toHaveAttribute('aria-busy', 'true');
    expect(button).toHaveAttribute('data-loading', 'true');
    expect(button).toBeDisabled();
    expect(screen.getByTestId('button-spinner')).toBeInTheDocument();
    await user.click(button);
    expect(onClick).not.toHaveBeenCalled();
  });

  it('fires onClick when idle', async () => {
    const user = userEvent.setup();
    const onClick = vi.fn();
    render(<Button onClick={onClick}>Add 30 min</Button>);
    await user.click(screen.getByRole('button'));
    expect(onClick).toHaveBeenCalledTimes(1);
  });
});
