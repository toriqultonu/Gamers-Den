import { expect, test } from '@playwright/test';
import { amountOf, chip, openScreen, segment, signIn } from './support/app';

/**
 * **print retry after simulated printer-offline** (frontend/ARCHITECTURE.md §6,
 * last scenario).
 *
 * A ticket that did not come out of the printer is money the customer has paid
 * for and cannot see, so S11 has to do three things and this checks all three:
 * say *why* it failed in the operator's words, offer the retry, and send **the
 * same stored bytes** rather than a fresh render — the paper is a record of the
 * transaction that happened, not of the moment Retry was pressed.
 *
 * **What is real here and what is not.** The sale, the transaction, the print
 * job and its stored render are the venue's own — nothing about them is staged.
 * The *transport* is: the venue's printer is a USB device, the fake port that
 * stands in for it under `dev` lives inside the backend JVM, and it has no HTTP
 * surface a browser can take offline (`FakePrinterPort` hands those hooks to
 * in-process tests only). Under `dev` the ticket therefore always prints, and a
 * backend that has printed a job answers `POST /print-jobs/{id}/retry` with 409
 * "only a FAILED job can be retried" — correctly.
 *
 * So two responses are staged, at the one boundary a browser owns, and only
 * two: the job read comes back FAILED/OFFLINE **over the real job's own body**,
 * and the retry write is answered as the queue would answer it. What is being
 * asserted is S11's side of the contract — the failure is explained in the
 * operator's words, Retry posts to the right route for the right job, and the
 * screen recovers the moment the job stops being FAILED. The queue's own side
 * (three attempts, identical bytes, the alert) is B18's, tested against the
 * fake port in-process where the hooks are.
 *
 * Making this end-to-end needs a dev-profile hook on the backend to put the
 * fake printer offline. That is a backend change and F17 is not the task that
 * makes it — it is written up as an open item instead.
 */
test('a failed ticket explains itself and retries onto the real queue', async ({ page }) => {
  await signIn(page);

  /* ------------------------------------------------- a real sale, a real print job */

  await openScreen(page, '/pos', 'pos-screen');
  await segment(page, 'Bill', 'Counter sale').click();
  await chip(page, 'Menu category', 'Beverage').click();
  await page.getByTestId('menu-card').filter({ hasText: 'Mineral Water' }).click();
  await expect.poll(() => amountOf(page, 'bill-due')).toBeGreaterThan(0);
  await page.getByTestId('settle').click();

  const receipt = page.getByTestId('receipt-preview');
  await expect(receipt.getByTestId('receipt-render')).toBeVisible();
  await receipt.getByTestId('open-print-preview').click();

  await expect(page).toHaveURL(/\/print\/\d+$/);
  const jobId = Number(page.url().match(/\/print\/(\d+)$/)?.[1]);
  expect(jobId).toBeGreaterThan(0);

  /* --------------------------------------------------- the printer goes off the bus */

  /** While true, the printer is "off the bus" and the job reads FAILED. */
  let offline = true;
  /** The last real job body, so the staged responses are the venue's shape. */
  let lastJob: Record<string, unknown> = {};

  await page.route(/\/api\/v1\/print-jobs\/\d+(\?.*)?$/, async (route) => {
    // Anything that is not a job goes through untouched — a reload asks before
    // the in-memory token has been restored, and that 401 is the app's to
    // handle (one silent refresh, then the real read arrives here).
    const response = await route.fetch();
    if (!response.ok()) {
      await route.fulfill({ response });
      return;
    }
    lastJob = await response.json();
    if (!offline) {
      await route.fulfill({ json: lastJob });
      return;
    }
    await route.fulfill({ json: { ...lastJob, status: 'FAILED', error: 'OFFLINE', attempts: 3 } });
  });

  /** The retry write: observed, and answered the way the queue answers it. */
  let retried: string | null = null;
  await page.route(/\/api\/v1\/print-jobs\/\d+\/retry$/, async (route) => {
    retried = route.request().url();
    // The cable is back in: the job goes round again, and every read after this
    // is the venue's own.
    offline = false;
    await route.fulfill({ json: { ...lastJob, status: 'QUEUED', error: null, attempts: 3 } });
  });

  await page.reload();
  const screen = page.getByTestId('print-preview-screen');
  await expect(screen).toBeVisible();

  await expect(page.getByTestId('print-rail')).toHaveAttribute('data-state', 'failed');
  await expect(page.getByTestId('print-failure')).toContainText('The printer is offline.');
  await expect(page.getByTestId('print-retry')).toBeVisible();
  // The stored render is still readable while the paper is not — the operator
  // can read the ticket out to the customer either way (§5.6).
  await expect(page.getByTestId('print-render')).toBeVisible();
  await expect(page.getByTestId('print-facts')).toContainText(`Job #${jobId}`);

  /* ------------------------------------------------ the cable is back in; retry */

  await page.getByTestId('print-retry').click();

  // Retry went to this job's own retry route — the same ticket, not a new one.
  await expect.poll(() => retried).toContain(`/print-jobs/${jobId}/retry`);

  // The queue took it back: no failure banner, and the rail is reporting an
  // ordinary status again over the same stored render.
  await expect(page.getByTestId('print-failure')).toHaveCount(0);
  await expect(page.getByTestId('print-status-note')).toBeVisible();
  await expect(page.getByTestId('print-rail')).not.toHaveAttribute('data-state', 'failed');
  await expect(page.getByTestId('print-render')).toBeVisible();
});
