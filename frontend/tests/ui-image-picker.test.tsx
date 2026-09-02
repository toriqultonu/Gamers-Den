/**
 * ImagePicker — docs/design.md §2 primitives row; the S13 login background.
 * States: empty, set, disabled.
 */

import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { ImagePicker } from '@/components/ui';

describe('ImagePicker', () => {
  it('renders the empty state with Remove disabled', () => {
    render(
      <ImagePicker
        label="Background image (left panel)"
        value={null}
        onChange={() => {}}
        emptyLabel="No image — the panel shows the accent color"
      />,
    );
    const preview = screen.getByTestId('image-picker-preview');
    expect(preview).toHaveAttribute('data-state', 'empty');
    expect(preview).toHaveTextContent('No image — the panel shows the accent color');
    expect(screen.getByRole('button', { name: 'Remove' })).toBeDisabled();
  });

  it('renders the set state and clears on Remove', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(
      <ImagePicker
        label="Background image"
        value="data:image/png;base64,AAAA"
        onChange={onChange}
        previewLabel="Login panel preview"
      />,
    );
    const preview = screen.getByTestId('image-picker-preview');
    expect(preview).toHaveAttribute('data-state', 'set');
    expect(preview).toHaveTextContent('Login panel preview');

    await user.click(screen.getByRole('button', { name: 'Remove' }));
    expect(onChange).toHaveBeenCalledWith(null);
  });

  it('reads a chosen image and hands back a data URL', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(<ImagePicker label="Background image" value={null} onChange={onChange} />);
    const input = screen.getByLabelText('Choose image');
    await user.upload(input, new File(['png-bytes'], 'venue.png', { type: 'image/png' }));
    await waitFor(() => expect(onChange).toHaveBeenCalled());
    expect(String(onChange.mock.calls[0][0])).toMatch(/^data:image\/png/);
  });

  it('renders disabled at 45% opacity', () => {
    render(<ImagePicker label="Background image" value={null} onChange={() => {}} disabled />);
    expect(screen.getByLabelText('Choose image')).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Remove' })).toBeDisabled();
  });
});
