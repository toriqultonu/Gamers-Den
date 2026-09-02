'use client';

/**
 * SegmentedChoice — docs/design.md §2 (primitives row).
 *
 * One-of-N in a flush segmented bar: a single 1px frame, hairline rules between
 * options, the selected option filled with accent. Exposed as a radiogroup so
 * arrow keys move the selection the way a native radio set does.
 */

import type { KeyboardEvent } from 'react';
import { cn } from './cn';

export type SegmentedOption<T extends string> = {
  value: T;
  label: string;
  disabled?: boolean;
};

export type SegmentedChoiceProps<T extends string> = {
  options: readonly SegmentedOption<T>[];
  value: T;
  onChange: (value: T) => void;
  /** Names the group for assistive tech; required when there is no visible label. */
  label?: string;
  className?: string;
};

export function SegmentedChoice<T extends string>({
  options,
  value,
  onChange,
  label,
  className,
}: SegmentedChoiceProps<T>) {
  const move = (event: KeyboardEvent<HTMLDivElement>, step: number) => {
    const enabled = options.filter((option) => !option.disabled);
    if (enabled.length === 0) return;
    event.preventDefault();
    const current = enabled.findIndex((option) => option.value === value);
    const next = enabled[(current + step + enabled.length) % enabled.length];
    onChange(next.value);
  };

  return (
    <div
      role="radiogroup"
      aria-label={label}
      className={cn('inline-flex border border-divider', className)}
      onKeyDown={(event) => {
        if (event.key === 'ArrowRight' || event.key === 'ArrowDown') move(event, 1);
        if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') move(event, -1);
      }}
    >
      {options.map((option) => {
        const selected = option.value === value;
        return (
          <button
            key={option.value}
            type="button"
            role="radio"
            aria-checked={selected}
            disabled={option.disabled}
            tabIndex={selected ? 0 : -1}
            data-state={selected ? 'selected' : 'unselected'}
            onClick={() => onChange(option.value)}
            className={cn(
              'cursor-pointer rounded-none px-3 py-1.5 text-[13px]',
              'border-l border-divider first:border-l-0',
              'focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-accent',
              'disabled:cursor-not-allowed disabled:opacity-45',
              selected
                ? 'bg-accent text-on-accent'
                : 'bg-transparent text-text hover:not-disabled:bg-neutral-200',
            )}
          >
            {option.label}
          </button>
        );
      })}
    </div>
  );
}
