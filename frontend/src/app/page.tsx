import { redirect } from 'next/navigation';

/**
 * The terminal has no landing page of its own — role-based redirects live in
 * the shell (TASK F04). Until then everyone lands on the Floor.
 */
export default function RootPage() {
  redirect('/floor');
}
