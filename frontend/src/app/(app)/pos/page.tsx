/**
 * S4 — Point of sale (TASK F07).
 *
 * A server component that mounts the screen and nothing else: the POS is a
 * form, an optimistic mutation and a live menu read all at once, which is the
 * client side of frontend/ARCHITECTURE.md §5.1.
 */

import { PosScreen } from '@/components/domain/pos-screen';

export default function PosPage() {
  return <PosScreen />;
}
