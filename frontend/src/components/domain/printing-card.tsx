'use client';

/**
 * The printing card on S10 — devices, the venue default, paper width, and a
 * test ticket (design.md §5; api-contract.md, "Print jobs").
 *
 * Three roles' worth of behaviour on one card, and the split is the API's:
 * every operator may read the list and fire a test ticket, only Admin may move
 * the default. So a manager sees the same devices with the same live status and
 * the same test button, and the "Make default" affordance simply is not drawn —
 * and would 403 if it were (frontend/ARCHITECTURE.md §4.3).
 *
 * The default is not local state. It is written by the server and read back
 * from `['printers']`, which is also the key `printer-status` pushes into, so a
 * printer that goes offline on another terminal's watch changes this card
 * without a reload — and the chosen default survives one, because it was never
 * kept here in the first place.
 */

import { useState } from 'react';
import { Printer as PrinterIcon } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { SegmentedChoice } from '@/components/ui/segmented-choice';
import { Tag } from '@/components/ui/tag';
import { errorNotice } from '@/lib/api';
import {
  PAPER_WIDTHS,
  PAPER_WIDTH_COLUMNS,
  VENUE_PAPER_WIDTH,
  printerReady,
  printerStatusLabel,
  usePrinters,
  useSetDefaultPrinter,
  useTestPrint,
} from '@/features/printing/printers';

export type PrintingCardProps = {
  /** Only Admin may move the venue's default (`PUT /printers/default`). */
  canSetDefault: boolean;
};

export function PrintingCard({ canSetDefault }: PrintingCardProps) {
  const printers = usePrinters();
  const setDefault = useSetDefaultPrinter();
  const test = useTestPrint();

  const [notice, setNotice] = useState<string | null>(null);
  const [queuedJob, setQueuedJob] = useState<number | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);

  const rows = printers.data ?? [];

  const choose = (printerId: string) => {
    setNotice(null);
    setQueuedJob(null);
    setBusyId(printerId);
    setDefault.mutate(
      { printerId },
      {
        onError: (error) =>
          setNotice(errorNotice(error, 'That printer could not be made the default.')),
        onSettled: () => setBusyId(null),
      },
    );
  };

  const fireTest = (printerId: string) => {
    setNotice(null);
    setQueuedJob(null);
    setBusyId(printerId);
    test.mutate(
      { printerId },
      {
        onSuccess: (job) => setQueuedJob(job.id ?? null),
        onError: (error) => setNotice(errorNotice(error, 'The test ticket could not be queued.')),
        onSettled: () => setBusyId(null),
      },
    );
  };

  return (
    <section data-testid="printing-card" className="flex flex-col gap-2">
      <h2 className="type-label opacity-55">Printing</h2>

      <div className="flex max-w-[720px] flex-col gap-3.5 border-2 border-text p-4">
        {notice ? (
          <p
            role="alert"
            data-testid="printing-notice"
            className="border-2 border-accent px-3 py-2 text-body text-accent-strong"
          >
            {notice}
          </p>
        ) : null}

        {queuedJob !== null ? (
          <p
            role="status"
            data-testid="printing-test-queued"
            className="border-2 border-divider px-3 py-2 text-body"
          >
            Test ticket queued as job #{queuedJob}. It is attempted like any receipt — check the
            printer, then the job in Print preview.
          </p>
        ) : null}

        {printers.isPending ? (
          <div data-testid="printers-skeleton" aria-busy="true" className="flex flex-col gap-2">
            {[0, 1].map((row) => (
              <div key={row} className="h-10 border-2 border-divider" />
            ))}
          </div>
        ) : printers.isError ? (
          <p role="alert" data-testid="printers-error" className="text-body text-accent-strong">
            {errorNotice(printers.error, 'The attached printers could not be listed.')}
          </p>
        ) : rows.length === 0 ? (
          <p data-testid="printers-empty" className="text-body opacity-60">
            No printer is attached to this terminal. Tickets will queue until one is.
          </p>
        ) : (
          <ul data-testid="printer-list" className="flex flex-col gap-2">
            {rows.map((printer) => {
              const id = printer.id ?? '';
              const busy = busyId === id;
              return (
                <li
                  key={id}
                  data-testid="printer-row"
                  data-default={printer.isDefault ? 'true' : undefined}
                  className="flex items-center gap-3 border-2 border-divider p-3"
                >
                  <PrinterIcon aria-hidden="true" className="size-4 shrink-0" strokeWidth={2} />
                  <div className="min-w-0 flex-1">
                    <p className="font-heading text-[15px] font-extrabold">
                      {printer.name ?? id}
                    </p>
                    <p className="text-[12px] opacity-60">{id}</p>
                  </div>

                  <Tag variant={printerReady(printer) ? 'neutral' : 'accent'}>
                    {printerStatusLabel(printer.status)}
                  </Tag>

                  {printer.isDefault ? (
                    <Tag variant="outline" data-testid="printer-default-tag">
                      Default
                    </Tag>
                  ) : canSetDefault ? (
                    <Button
                      variant="secondary"
                      size="sm"
                      loading={busy && setDefault.isPending}
                      onClick={() => choose(id)}
                    >
                      Make default
                    </Button>
                  ) : null}

                  <Button
                    variant="ghost"
                    size="sm"
                    loading={busy && test.isPending}
                    onClick={() => fireTest(id)}
                  >
                    Test ticket
                  </Button>
                </li>
              );
            })}
          </ul>
        )}

        {!canSetDefault ? (
          <p data-testid="printer-default-locked" className="text-[12px] opacity-60">
            The venue&rsquo;s default printer is set by the owner. You can still test any device
            from here.
          </p>
        ) : null}

        <div className="h-0.5 bg-divider" />

        <div className="flex flex-col gap-1.5">
          <p className="type-label opacity-55">Paper width</p>
          <SegmentedChoice
            label="Paper width"
            value={VENUE_PAPER_WIDTH}
            onChange={() => undefined}
            options={PAPER_WIDTHS.map((width) => ({
              value: width,
              label: `${width} mm`,
              // OPEN FLAG (design.md §8.1): the printer model is unconfirmed and
              // 58 mm is a venue config switch — there is no endpoint to move
              // it, so the control reports the venue's width rather than
              // offering a choice that would write nowhere.
              disabled: width !== VENUE_PAPER_WIDTH,
            }))}
          />
          <p data-testid="paper-width-note" className="text-[12px] opacity-60">
            Templates lay out to {PAPER_WIDTH_COLUMNS[VENUE_PAPER_WIDTH]} columns at{' '}
            {VENUE_PAPER_WIDTH} mm / Font A. The printer model is still unconfirmed — 58 mm is a
            venue configuration switch (<span className="tabular">gamersden.printing</span>), not a
            terminal setting, so it is shown here and changed on the box.
          </p>
        </div>
      </div>
    </section>
  );
}
