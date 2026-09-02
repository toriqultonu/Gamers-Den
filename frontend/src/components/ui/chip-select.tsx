'use client';

/**
 * ChipSelect — docs/design.md §2 (primitives row).
 *
 * The wrapping chip rows the prototype uses for consoles, members and payment
 * methods: 2px rule, accent fill when on. Single-select by default; pass
 * `multiple` for the sets where more than one chip can be on at once.
 */

import { cn } from './cn';

export type ChipOption<T extends string> = {
  value: T;
  label: string;
  disabled?: boolean;
};

type SingleProps<T extends string> = {
  multiple?: false;
  value: T | null;
  onChange: (value: T) => void;
};

type MultiProps<T extends string> = {
  multiple: true;
  value: readonly T[];
  onChange: (value: T[]) => void;
};

export type ChipSelectProps<T extends string> = {
  options: readonly ChipOption<T>[];
  /** Names the group for assistive tech; required when there is no visible label. */
  label?: string;
  className?: string;
} & (SingleProps<T> | MultiProps<T>);

export function ChipSelect<T extends string>({
  options,
  label,
  className,
  ...selection
}: ChipSelectProps<T>) {
  const isOn = (value: T) =>
    selection.multiple ? selection.value.includes(value) : selection.value === value;

  const toggle = (value: T) => {
    if (selection.multiple) {
      const next = selection.value.includes(value)
        ? selection.value.filter((entry) => entry !== value)
        : [...selection.value, value];
      selection.onChange(next);
      return;
    }
    selection.onChange(value);
  };

  return (
    <div role="group" aria-label={label} className={cn('flex flex-wrap gap-1.5', className)}>
      {options.map((option) => {
        const on = isOn(option.value);
        return (
          <button
            key={option.value}
            type="button"
            aria-pressed={on}
            disabled={option.disabled}
            data-state={on ? 'selected' : 'unselected'}
            onClick={() => toggle(option.value)}
            className={cn(
              'cursor-pointer rounded-none border-2 px-3.5 py-2',
              'font-heading text-[13px] font-extrabold',
              'focus-visible:outline-2 focus-visible:outline-accent focus-visible:outline-offset-2',
              'disabled:cursor-not-allowed disabled:opacity-45',
              on
                ? 'border-accent bg-accent text-on-accent'
                : 'border-divider bg-transparent text-text hover:not-disabled:bg-neutral-200',
            )}
          >
            {option.label}
          </button>
        );
      })}
    </div>
  );
}
