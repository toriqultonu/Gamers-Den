/**
 * Auto-lock — S13's "Auto-lock (PIN to unlock): Off / 2 / 5 / 10 min".
 *
 * The point of the feature is that a counter left alone locks itself, and that
 * a counter someone is standing at never does. Both halves are asserted here.
 */

import { render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAutoLock } from '@/features/auth/use-auto-lock';

function Probe({ minutes, enabled = true, onLock }: {
  minutes: number;
  enabled?: boolean;
  onLock: () => void;
}) {
  useAutoLock({ minutes, enabled, onLock });
  return <div>terminal</div>;
}

/** Operator input, as the browser reports it. */
function touchTheTerminal() {
  window.dispatchEvent(new KeyboardEvent('keydown', { key: 'a' }));
}

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

describe('an idle terminal', () => {
  it('locks after the configured minutes', () => {
    const onLock = vi.fn();
    render(<Probe minutes={2} onLock={onLock} />);

    vi.advanceTimersByTime(119_000);
    expect(onLock).not.toHaveBeenCalled();

    vi.advanceTimersByTime(1_000);
    expect(onLock).toHaveBeenCalledTimes(1);
  });

  it('honours each of the S13 choices', () => {
    for (const minutes of [2, 5, 10]) {
      const onLock = vi.fn();
      const view = render(<Probe minutes={minutes} onLock={onLock} />);

      vi.advanceTimersByTime(minutes * 60_000 - 1_000);
      expect(onLock).not.toHaveBeenCalled();
      vi.advanceTimersByTime(1_000);
      expect(onLock).toHaveBeenCalledTimes(1);

      view.unmount();
    }
  });
});

describe('a terminal someone is using', () => {
  it('re-arms on operator input instead of locking', () => {
    const onLock = vi.fn();
    render(<Probe minutes={2} onLock={onLock} />);

    vi.advanceTimersByTime(119_000);
    touchTheTerminal();

    // The old deadline passes with nothing happening…
    vi.advanceTimersByTime(2_000);
    expect(onLock).not.toHaveBeenCalled();

    // …and the new one is a full two minutes after the keystroke.
    vi.advanceTimersByTime(118_000);
    expect(onLock).toHaveBeenCalledTimes(1);
  });

  it('re-arms on a pointer press as well as a key', () => {
    const onLock = vi.fn();
    render(<Probe minutes={2} onLock={onLock} />);

    vi.advanceTimersByTime(119_000);
    window.dispatchEvent(new Event('pointerdown'));
    vi.advanceTimersByTime(2_000);

    expect(onLock).not.toHaveBeenCalled();
  });
});

describe('switched off', () => {
  it('never locks when the setting is Off (0 minutes)', () => {
    const onLock = vi.fn();
    render(<Probe minutes={0} onLock={onLock} />);

    vi.advanceTimersByTime(60 * 60_000);
    expect(onLock).not.toHaveBeenCalled();
  });

  it('never locks while disabled — nobody is signed in, or the lock is already up', () => {
    const onLock = vi.fn();
    render(<Probe minutes={2} enabled={false} onLock={onLock} />);

    vi.advanceTimersByTime(10 * 60_000);
    expect(onLock).not.toHaveBeenCalled();
  });

  it('stops counting once the shell unmounts', () => {
    const onLock = vi.fn();
    const view = render(<Probe minutes={2} onLock={onLock} />);

    view.unmount();
    vi.advanceTimersByTime(10 * 60_000);
    expect(onLock).not.toHaveBeenCalled();
  });
});
