/**
 * S6 — Members (TASK F09).
 *
 * A server component that mounts the screen: the directory is a debounced
 * search, the rail is a selection, and both wallet writes are forms — the
 * client side of frontend/ARCHITECTURE.md §5.1.
 */

import { MembersScreen } from '@/components/domain/members-screen';

export default function MembersPage() {
  return <MembersScreen />;
}
