'use client';

/**
 * FieldInput — docs/design.md §2 (primitives row).
 *
 * Label + control + inline error. States: default, hover, focus, error,
 * disabled. Errors are announced and never clear what was typed
 * (frontend/ARCHITECTURE.md §4.4: "an error never destroys entered data").
 */

import type { InputHTMLAttributes, ReactNode } from 'react';
import { forwardRef, useId } from 'react';
import { cn } from './cn';

export type FieldInputProps = Omit<InputHTMLAttributes<HTMLInputElement>, 'children'> & {
  label: string;
  /** Inline error text — renders under the control and marks it invalid. */
  error?: string;
  /** Quiet helper line, hidden while an error is showing. */
  hint?: ReactNode;
  /** Trailing adornment inside the field row (e.g. a unit or ৳). */
  suffix?: ReactNode;
};

export const FieldInput = forwardRef<HTMLInputElement, FieldInputProps>(function FieldInput(
  { label, error, hint, suffix, id, className, disabled, ...props },
  ref,
) {
  const reactId = useId();
  const inputId = id ?? `${reactId}-input`;
  const errorId = `${inputId}-error`;
  const hintId = `${inputId}-hint`;
  const describedBy = error ? errorId : hint ? hintId : undefined;

  return (
    <div className="flex flex-col gap-1" data-invalid={error ? true : undefined}>
      <label htmlFor={inputId} className="text-[12px] opacity-70">
        {label}
      </label>
      <div className="flex items-center gap-2">
        <input
          ref={ref}
          id={inputId}
          disabled={disabled}
          aria-invalid={error ? true : undefined}
          aria-describedby={describedBy}
          className={cn(
            'min-h-9 w-full rounded-none bg-surface px-2.5 py-1.5 text-body text-text',
            'border border-divider caret-accent',
            'hover:not-disabled:border-neutral-500',
            'focus-visible:border-accent focus-visible:outline-2 focus-visible:outline-accent focus-visible:outline-offset-0',
            'disabled:cursor-not-allowed disabled:opacity-45',
            error && 'border-accent',
            className,
          )}
          {...props}
        />
        {suffix ? <span className="shrink-0 text-[12px] opacity-60">{suffix}</span> : null}
      </div>
      {error ? (
        <p id={errorId} role="alert" className="text-[12px] text-accent-strong">
          {error}
        </p>
      ) : hint ? (
        <p id={hintId} className="text-[12px] opacity-55">
          {hint}
        </p>
      ) : null}
    </div>
  );
});
