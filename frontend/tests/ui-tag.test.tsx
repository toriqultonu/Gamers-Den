/**
 * Tag — docs/design.md §2: variants accent · neutral · outline, state static.
 */

import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { TAG_VARIANTS, Tag, type TagVariant } from '@/components/ui';

const INK: Record<TagVariant, string[]> = {
  // accent tag = tint fill + ramp-800 ink (design.md §3 contrast rules)
  accent: ['bg-accent-tint', 'text-accent-800'],
  neutral: ['bg-neutral-100', 'text-neutral-800'],
  // outline draws its rule in accent, but the label stays accent-strong
  outline: ['border-accent', 'text-accent-strong'],
};

describe('Tag', () => {
  it.each(TAG_VARIANTS)('renders the %s variant', (variant) => {
    render(<Tag variant={variant}>PS5</Tag>);
    const tag = screen.getByText('PS5');
    expect(tag).toHaveAttribute('data-variant', variant);
    for (const className of INK[variant]) expect(tag).toHaveClass(className);
  });

  it('defaults to neutral', () => {
    render(<Tag>Paid</Tag>);
    expect(screen.getByText('Paid')).toHaveAttribute('data-variant', 'neutral');
  });

  it('never paints body copy in raw accent', () => {
    for (const variant of TAG_VARIANTS) {
      const { unmount } = render(<Tag variant={variant}>Live</Tag>);
      expect(screen.getByText('Live')).not.toHaveClass('text-accent');
      unmount();
    }
  });
});
