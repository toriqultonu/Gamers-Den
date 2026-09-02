'use client';

/**
 * Dialog — docs/design.md §2 (primitives row).
 *
 * Modal panel over a scrim: 2px rule, radius 0, the only shadow the system
 * allows outside the app frame and the receipt preview. Escape and a backdrop
 * click both close; focus moves into the panel on open and returns to the
 * opener on close, and Tab is trapped inside the panel while it is open.
 */

import type { KeyboardEvent, ReactNode } from 'react';
import { useCallback, useEffect, useId, useRef } from 'react';
import { Button } from './button';
import { cn } from './cn';

export type DialogProps = {
  open: boolean;
  onClose: () => void;
  title: string;
  /** Optional sub-line under the title; also names the dialog for assistive tech. */
  description?: string;
  children?: ReactNode;
  /** Action row pinned to the bottom of the panel. */
  footer?: ReactNode;
  className?: string;
};

const FOCUSABLE =
  'a[href],button:not([disabled]),input:not([disabled]),select:not([disabled]),textarea:not([disabled]),[tabindex]:not([tabindex="-1"])';

export function Dialog({
  open,
  onClose,
  title,
  description,
  children,
  footer,
  className,
}: DialogProps) {
  const panelRef = useRef<HTMLDivElement>(null);
  const openerRef = useRef<Element | null>(null);
  const id = useId();
  const titleId = `${id}-title`;
  const descId = `${id}-desc`;

  useEffect(() => {
    if (!open) return;
    openerRef.current = document.activeElement;
    const panel = panelRef.current;
    const first = panel?.querySelector<HTMLElement>(FOCUSABLE);
    (first ?? panel)?.focus();
    return () => {
      const opener = openerRef.current;
      if (opener instanceof HTMLElement) opener.focus();
    };
  }, [open]);

  const onKeyDown = useCallback(
    (event: KeyboardEvent<HTMLDivElement>) => {
      if (event.key === 'Escape') {
        event.stopPropagation();
        onClose();
        return;
      }
      if (event.key !== 'Tab') return;
      const nodes = Array.from(panelRef.current?.querySelectorAll<HTMLElement>(FOCUSABLE) ?? []);
      if (nodes.length === 0) return;
      const first = nodes[0];
      const last = nodes[nodes.length - 1];
      const active = document.activeElement;
      if (event.shiftKey && (active === first || active === panelRef.current)) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && active === last) {
        event.preventDefault();
        first.focus();
      }
    },
    [onClose],
  );

  if (!open) return null;

  return (
    <div
      data-testid="dialog-backdrop"
      className="fixed inset-0 z-50 grid place-items-center bg-[color-mix(in_srgb,var(--gd-neutral-900)_50%,transparent)] p-4"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={description ? descId : undefined}
        tabIndex={-1}
        onKeyDown={onKeyDown}
        className={cn(
          'flex w-[min(440px,100%)] flex-col gap-3 border-2 border-divider bg-surface p-4 shadow-lg',
          'focus-visible:outline-2 focus-visible:outline-accent focus-visible:outline-offset-2',
          className,
        )}
      >
        <div className="flex items-start gap-3">
          <div className="min-w-0 flex-1">
            <h2 id={titleId} className="font-heading text-h3 font-extrabold">
              {title}
            </h2>
            {description ? (
              <p id={descId} className="mt-1 text-[13px] opacity-60">
                {description}
              </p>
            ) : null}
          </div>
          <Button variant="ghost" size="sm" onClick={onClose} className="ml-auto shrink-0">
            Close
          </Button>
        </div>
        {children ? <div className="flex flex-col gap-3">{children}</div> : null}
        {footer ? <div className="flex items-center justify-end gap-2">{footer}</div> : null}
      </div>
    </div>
  );
}
