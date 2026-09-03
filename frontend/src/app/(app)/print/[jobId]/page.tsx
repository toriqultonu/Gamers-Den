/**
 * S11 — Print preview. Scaffolded in TASK F01; built in TASK F15, which owns
 * the job-state table (rendering/ready/queued/failed/retry) and the reprint
 * reason picker. F08 built the reads it will stand on
 * (`features/printing/use-print-job.ts`) and the `ReceiptPreview` that draws
 * them in the POS ticket column.
 *
 * Always renders the server's stored render (frontend/ARCHITECTURE.md §5.6) —
 * never a client-side redraw of the receipt.
 */
export default async function PrintPreviewPage({
  params,
}: {
  params: Promise<{ jobId: string }>;
}) {
  const { jobId } = await params;

  return (
    <section className="flex flex-col gap-2 p-8">
      <p className="type-label text-accent-strong">S11</p>
      <h1 className="text-h2">Print preview</h1>
      <p className="tabular text-body opacity-75">Job {jobId} — scaffolded in F01, built in F15.</p>
    </section>
  );
}
