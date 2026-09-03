'use client';

/**
 * The device half of printing — `['printers']`, its default, and the test
 * ticket (api-contract.md, "Print jobs"; design.md §5).
 *
 * The list is every operator's business: it is how the counter learns the
 * printer is out of paper before a customer is standing in front of it, and it
 * is the one key SSE pushes whole (`printer-status` → `setQueryData`,
 * lib/sse.ts). Choosing the venue's printer is Admin's, "as terminal
 * configuration is".
 *
 * The test ticket goes through the queue like any receipt — claimed, attempted,
 * DONE or FAILED — so what it proves is the whole path and not just the cable.
 * It is therefore reported by job id, and the caller polls it with
 * `use-print-job.ts` exactly as a settle does.
 */

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, type Schemas } from '@/lib/api';
import { queryKeys } from '@/lib/query-keys';

export type Printer = Schemas['Printer'];
export type PrintJob = Schemas['PrintJob'];

/** The device states `GET /printers` polls live off the port. */
export const PRINTER_STATUS_LABELS: Record<string, string> = {
  ONLINE: 'Online',
  OFFLINE: 'Offline',
  OUT_OF_PAPER: 'Out of paper',
  COVER_OPEN: 'Cover open',
};

export function printerStatusLabel(status: string | undefined): string {
  return PRINTER_STATUS_LABELS[status ?? ''] ?? status ?? 'Unknown';
}

/** Only ONLINE is a printer you would send a customer's receipt to. */
export function printerReady(printer: Printer): boolean {
  return printer.status === 'ONLINE';
}

/**
 * Paper width. **OPEN FLAG** (design.md §8.1, TASKLIST global rule 9): the
 * printer model is unconfirmed, 80 mm / 203 dpi / 48 columns is the documented
 * assumption, and 58 mm is "a config switch" — `gamersden.printing.*` on the
 * venue box, with no field on `TerminalSettings` and no endpoint to move it.
 * The card therefore *shows* the width the renders are laid out to and says
 * where it changes, rather than offering a toggle that would write nowhere.
 */
export const PAPER_WIDTHS = ['80', '58'] as const;
export type PaperWidth = (typeof PAPER_WIDTHS)[number];
export const VENUE_PAPER_WIDTH: PaperWidth = '80';
export const PAPER_WIDTH_COLUMNS: Record<PaperWidth, number> = { '80': 48, '58': 32 };

export function printersQueryOptions() {
  return {
    queryKey: queryKeys.printers.all(),
    queryFn: () => api.get<Printer[]>('/printers'),
    // Polled off the device on every read, so it is worth asking again when a
    // card is looked at — but `printer-status` normally beats the refetch to it.
    staleTime: 10_000,
  };
}

/** `GET /printers` — default first, live status per row. */
export function usePrinters(options: { enabled?: boolean } = {}) {
  return useQuery({ ...printersQueryOptions(), enabled: options.enabled ?? true });
}

export function defaultPrinter(printers: Printer[] | undefined): Printer | undefined {
  return (printers ?? []).find((printer) => printer.isDefault);
}

/**
 * `PUT /printers/default` (Admin) — the choice the whole venue prints on.
 *
 * Never optimistic and never local: the answer is the authoritative list with
 * the flag moved, and it is written straight into `['printers']` so every card
 * on the terminal agrees at once. A refusal (404 on an id nothing answers to,
 * 403 for a manager) leaves the previous default exactly where it was.
 */
export function useSetDefaultPrinter() {
  const client = useQueryClient();
  return useMutation<Printer, unknown, { printerId: string }>({
    mutationFn: ({ printerId }) => api.put<Printer>('/printers/default', { printerId }),
    onSuccess: (chosen) => {
      client.setQueryData<Printer[]>(queryKeys.printers.all(), (current) =>
        (current ?? []).map((printer) =>
          printer.id === chosen.id ? chosen : { ...printer, isDefault: false },
        ),
      );
      // The server orders the list default-first; re-read rather than re-sort.
      void client.invalidateQueries({ queryKey: queryKeys.printers.all() });
    },
  });
}

/** `POST /printers/{id}/test` — an ordinary queued job, reported by id. */
export function useTestPrint() {
  return useMutation<PrintJob, unknown, { printerId: string }>({
    mutationFn: ({ printerId }) =>
      api.post<PrintJob>(`/printers/${encodeURIComponent(printerId)}/test`),
  });
}
