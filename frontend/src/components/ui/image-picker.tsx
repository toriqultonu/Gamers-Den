'use client';

/**
 * ImagePicker — docs/design.md §2 (primitives row); S13 login background.
 *
 * Preview box + "Choose image" (a label wrapping a hidden file input, so it is
 * still a real keyboard-reachable control) + "Remove", disabled while empty.
 * The picker hands the caller a data URL for the preview and, alongside it,
 * the `File` itself — S13 uploads that to `POST /terminal-settings/login-bg`
 * (multipart, validated by its own bytes). Where it is stored is the screen's
 * business, not the primitive's.
 */

import { useId, useRef, useState } from 'react';
import { Button } from './button';
import { cn } from './cn';

export type ImagePickerProps = {
  label: string;
  /** Current image as a data/URL string, or null when unset. */
  value: string | null;
  /** The preview URL, plus the chosen file when this was a pick rather than a clear. */
  onChange: (value: string | null, file?: File) => void;
  /** Caption drawn over the preview when an image is set. */
  previewLabel?: string;
  /** Caption drawn in the empty preview box. */
  emptyLabel?: string;
  disabled?: boolean;
  className?: string;
};

export function ImagePicker({
  label,
  value,
  onChange,
  previewLabel = 'Preview',
  emptyLabel = 'No image',
  disabled = false,
  className,
}: ImagePickerProps) {
  const inputRef = useRef<HTMLInputElement>(null);
  const inputId = `${useId()}-file`;
  const [error, setError] = useState<string | null>(null);

  const pick = (file: File | undefined) => {
    if (!file) return;
    if (!file.type.startsWith('image/')) {
      setError('That file is not an image.');
      return;
    }
    const reader = new FileReader();
    reader.onload = () => {
      setError(null);
      onChange(typeof reader.result === 'string' ? reader.result : null, file);
    };
    reader.onerror = () => setError('Could not read that file.');
    reader.readAsDataURL(file);
  };

  return (
    <div className={cn('flex flex-col gap-2', className)}>
      <span className="text-[12px] opacity-70">{label}</span>
      <div
        data-testid="image-picker-preview"
        data-state={value ? 'set' : 'empty'}
        style={
          value
            ? {
                backgroundImage: `linear-gradient(rgba(15,10,8,.45), rgba(15,10,8,.6)), url(${value})`,
                backgroundSize: 'cover',
                backgroundPosition: 'center',
              }
            : undefined
        }
        className={cn(
          'grid h-28 place-items-center border-2 border-divider text-[12px]',
          value ? 'text-white' : 'bg-surface opacity-55',
        )}
      >
        {value ? previewLabel : emptyLabel}
      </div>
      <div className="flex items-center gap-2">
        <label
          htmlFor={inputId}
          className={cn(
            'inline-flex cursor-pointer items-center justify-center gap-2 border border-divider px-4 py-2',
            'font-heading text-body font-extrabold text-text',
            'hover:bg-neutral-200',
            'focus-within:outline-2 focus-within:outline-accent focus-within:outline-offset-2',
            disabled && 'cursor-not-allowed opacity-45',
          )}
        >
          Choose image
          <input
            ref={inputRef}
            id={inputId}
            type="file"
            accept="image/*"
            disabled={disabled}
            className="sr-only"
            onChange={(event) => pick(event.target.files?.[0])}
          />
        </label>
        <Button
          variant="ghost"
          onClick={() => {
            setError(null);
            onChange(null);
            if (inputRef.current) inputRef.current.value = '';
          }}
          disabled={disabled || !value}
        >
          Remove
        </Button>
      </div>
      {error ? (
        <p role="alert" className="text-[12px] text-accent-strong">
          {error}
        </p>
      ) : null}
    </div>
  );
}
