'use client';

/**
 * The 4-digit PIN entry — S1's pad, and the same pad behind the auto-lock.
 *
 * Two ways in, one value: the keypad for a counter with a touchscreen or a
 * mouse, and a real password field for the staff member who just types it.
 * Nothing here validates the PIN; the server does (`POST /auth/login`), and it
 * is the only thing that ever sees it.
 */

import { useId, type KeyboardEvent } from 'react';
import { Button, cn } from '@/components/ui';

export const PIN_LENGTH = 4;

const KEYS = ['1', '2', '3', '4', '5', '6', '7', '8', '9'] as const;

export type PinPadProps = {
  value: string;
  onChange: (value: string) => void;
  /** Enter on the field, or a full PIN typed on the pad. */
  onSubmit?: () => void;
  disabled?: boolean;
  /** Inline error under the field — a wrong PIN never clears what was typed. */
  error?: string;
  label?: string;
  autoFocus?: boolean;
};

const onlyDigits = (raw: string) => raw.replace(/\D/g, '').slice(0, PIN_LENGTH);

export function PinPad({
  value,
  onChange,
  onSubmit,
  disabled = false,
  error,
  label = 'PIN',
  autoFocus = false,
}: PinPadProps) {
  const id = useId();
  const inputId = `${id}-pin`;
  const errorId = `${id}-pin-error`;

  const press = (digit: string) => {
    if (disabled) return;
    onChange(onlyDigits(value + digit));
  };

  const handleKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === 'Enter' && onSubmit) {
      event.preventDefault();
      onSubmit();
    }
  };

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-col gap-1">
        <label htmlFor={inputId} className="type-label opacity-55">
          {label}
        </label>
        <input
          id={inputId}
          type="password"
          inputMode="numeric"
          autoComplete="off"
          autoFocus={autoFocus}
          maxLength={PIN_LENGTH}
          value={value}
          disabled={disabled}
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? errorId : undefined}
          onChange={(event) => onChange(onlyDigits(event.target.value))}
          onKeyDown={handleKeyDown}
          placeholder="••••"
          className={cn(
            'min-h-12 w-full rounded-none border border-divider bg-surface px-3',
            'text-h3 tracking-[0.5em] text-text tabular caret-accent',
            'focus-visible:border-accent focus-visible:outline-2 focus-visible:outline-accent',
            'disabled:cursor-not-allowed disabled:opacity-45',
            error && 'border-accent',
          )}
        />
        {error ? (
          <p id={errorId} role="alert" className="text-[12px] text-accent-strong">
            {error}
          </p>
        ) : null}
      </div>

      <div className="grid grid-cols-3 gap-px bg-divider" role="group" aria-label="PIN keypad">
        {KEYS.map((key) => (
          <Button
            key={key}
            variant="secondary"
            size="lg"
            disabled={disabled}
            onClick={() => press(key)}
            className="h-12 border-0 bg-surface text-h3"
          >
            {key}
          </Button>
        ))}
        <Button
          variant="secondary"
          size="lg"
          disabled={disabled || value.length === 0}
          onClick={() => onChange('')}
          className="h-12 border-0 bg-surface text-[12px]"
        >
          Clear
        </Button>
        <Button
          variant="secondary"
          size="lg"
          disabled={disabled}
          onClick={() => press('0')}
          className="h-12 border-0 bg-surface text-h3"
        >
          0
        </Button>
        <Button
          variant="secondary"
          size="lg"
          aria-label="Delete last digit"
          disabled={disabled || value.length === 0}
          onClick={() => onChange(value.slice(0, -1))}
          className="h-12 border-0 bg-surface text-[12px]"
        >
          Delete
        </Button>
      </div>
    </div>
  );
}
