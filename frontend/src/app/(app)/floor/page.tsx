/**
 * S3 — Floor (TASK F06).
 *
 * A server component whose only job is to mount the screen: everything on the
 * floor is a timer, a subscription or an optimistic mutation, which is exactly
 * the list frontend/ARCHITECTURE.md §5.1 puts on the client side of the line.
 */

import { FloorScreen } from '@/components/domain/floor-screen';

export default function FloorPage() {
  return <FloorScreen />;
}
