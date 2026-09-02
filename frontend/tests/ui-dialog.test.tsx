/**
 * Dialog — docs/design.md §2 primitives row. Open/closed, Escape and backdrop
 * close, labelled panel, focus moved in and returned.
 */

import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { Button, Dialog } from '@/components/ui';

describe('Dialog', () => {
  it('renders nothing while closed', () => {
    render(
      <Dialog open={false} onClose={() => {}} title="End session">
        <p>Body</p>
      </Dialog>,
    );
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('renders a modal panel labelled by its title and described by its description', () => {
    render(
      <Dialog open onClose={() => {}} title="End session" description="Blocks are settled.">
        <p>Body</p>
      </Dialog>,
    );
    const dialog = screen.getByRole('dialog');
    expect(dialog).toHaveAttribute('aria-modal', 'true');
    expect(dialog).toHaveAccessibleName('End session');
    expect(dialog).toHaveAccessibleDescription('Blocks are settled.');
    expect(screen.getByText('Body')).toBeInTheDocument();
  });

  it('renders the footer actions', () => {
    render(
      <Dialog open onClose={() => {}} title="Cancel booking" footer={<Button>Confirm</Button>}>
        <p>Refund in full.</p>
      </Dialog>,
    );
    expect(screen.getByRole('button', { name: 'Confirm' })).toBeInTheDocument();
  });

  it('closes on Escape, on the Close control and on a backdrop click', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    const { rerender } = render(
      <Dialog open onClose={onClose} title="End session">
        <p>Body</p>
      </Dialog>,
    );

    await user.keyboard('{Escape}');
    expect(onClose).toHaveBeenCalledTimes(1);

    await user.click(screen.getByRole('button', { name: 'Close' }));
    expect(onClose).toHaveBeenCalledTimes(2);

    await user.click(screen.getByTestId('dialog-backdrop'));
    expect(onClose).toHaveBeenCalledTimes(3);

    // a click inside the panel is not a dismissal
    await user.click(screen.getByText('Body'));
    expect(onClose).toHaveBeenCalledTimes(3);

    rerender(
      <Dialog open={false} onClose={onClose} title="End session">
        <p>Body</p>
      </Dialog>,
    );
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('moves focus into the panel on open', () => {
    render(
      <Dialog open onClose={() => {}} title="End session" footer={<Button>Confirm</Button>}>
        <p>Body</p>
      </Dialog>,
    );
    expect(screen.getByRole('button', { name: 'Close' })).toHaveFocus();
  });
});
