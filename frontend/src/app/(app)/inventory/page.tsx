import { InventoryScreen } from '@/components/domain/inventory-screen';

/**
 * S5 — Inventory (TASK F13).
 *
 * Open to every role (`NAV` carries it for all three) and read-only by design:
 * the stock table reports, and the corrections happen in Setup where the write
 * is guarded and audited.
 */
export default function InventoryPage() {
  return <InventoryScreen />;
}
