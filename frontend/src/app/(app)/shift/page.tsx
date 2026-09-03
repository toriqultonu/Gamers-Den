import { ShiftScreen } from '@/components/domain/shift-screen';

/**
 * S7 — Shift close (TASK F12).
 *
 * All roles reach it (api-contract.md §1: "Expenses, shift open/close ✓ ✓ ✓"),
 * with one nuance the API enforces rather than the UI — a cashier may close
 * only their own shift, and a manager may close anyone's, which is what a
 * handover with the cashier already gone looks like. So there is no guard here;
 * the screen renders the 403 as an access notice if the server ever answers one.
 */
export default function ShiftPage() {
  return <ShiftScreen />;
}
