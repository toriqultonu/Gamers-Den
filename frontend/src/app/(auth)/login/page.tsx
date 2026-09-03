import { Suspense } from 'react';
import { LoginScreen } from '@/components/domain/login-screen';

/**
 * S1 — Sign in. The screen itself is a client component (a PIN pad, a picker
 * and a form); the Suspense boundary is what `useSearchParams` needs to read
 * the `?next=` the middleware attached.
 */
export default function LoginPage() {
  return (
    <Suspense fallback={<div className="min-h-screen bg-bg" />}>
      <LoginScreen />
    </Suspense>
  );
}
