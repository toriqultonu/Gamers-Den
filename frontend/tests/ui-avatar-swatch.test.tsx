/**
 * AvatarSwatch — docs/design.md §2 primitives row; the S13 avatar colour.
 */

import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { AVATAR_COLORS, AVATAR_SWATCH_SIZES, AvatarSwatch } from '@/components/ui';

describe('AvatarSwatch', () => {
  it.each(AVATAR_SWATCH_SIZES)('renders the %s size', (size) => {
    render(<AvatarSwatch size={size} color="#ec3013" initials="SA" />);
    expect(screen.getByText('SA')).toBeInTheDocument();
  });

  it('renders a static avatar in the chosen colour', () => {
    render(<AvatarSwatch color="#0f62fe" initials="TA" />);
    const avatar = screen.getByText('TA');
    expect(avatar.tagName.toLowerCase()).toBe('span');
    expect(avatar).toHaveStyle({ backgroundColor: '#0f62fe' });
  });

  it('falls back to the ink/ground pair with no colour set', () => {
    render(<AvatarSwatch initials="AD" />);
    expect(screen.getByText('AD')).toHaveStyle({ backgroundColor: 'var(--gd-text)' });
  });

  it('renders the palette as pressable chips and reports the pick', async () => {
    const user = userEvent.setup();
    const onSelect = vi.fn();
    render(
      <div>
        {AVATAR_COLORS.map((color) => (
          <AvatarSwatch
            key={color}
            color={color}
            selected={color === '#198038'}
            onSelect={() => onSelect(color)}
          />
        ))}
      </div>,
    );
    const chips = screen.getAllByRole('button');
    expect(chips).toHaveLength(AVATAR_COLORS.length);

    const selected = screen.getByRole('button', { name: 'Avatar colour #198038' });
    expect(selected).toHaveAttribute('aria-pressed', 'true');
    expect(selected).toHaveClass('outline-[3px]', 'outline-text');

    await user.click(screen.getByRole('button', { name: 'Avatar colour #ec3013' }));
    expect(onSelect).toHaveBeenCalledWith('#ec3013');
  });

  it('marks an unselected chip unpressed', () => {
    render(<AvatarSwatch color="#8a3ffc" onSelect={() => {}} />);
    expect(screen.getByRole('button')).toHaveAttribute('aria-pressed', 'false');
  });
});
