/**
 * Money — integer BDT, Western grouping, real minus sign
 * (docs/api-contract.md §1; the prototype prints ৳392,400).
 */

import { describe, expect, it } from 'vitest';
import {
  CURRENCY_SYMBOL,
  bookingTotal,
  formatAmount,
  formatBDT,
  parseAmount,
  playAmount,
} from '@/lib/money';

describe('formatBDT', () => {
  it('prints whole taka with the ৳ sign', () => {
    expect(formatBDT(0)).toBe('৳0');
    expect(formatBDT(150)).toBe('৳150');
    expect(formatBDT(1250)).toBe('৳1,250');
    expect(CURRENCY_SYMBOL).toBe('৳');
  });

  it('groups in thousands, not lakhs — the prototype prints ৳392,400', () => {
    expect(formatBDT(392_400)).toBe('৳392,400');
    expect(formatBDT(9_420)).toBe('৳9,420');
  });

  it('renders a refund with a real minus sign, ahead of the symbol', () => {
    expect(formatBDT(-150)).toBe('−৳150');
    expect(formatBDT(-420)).toBe('−৳420');
    // U+2212, not a hyphen — it lines up in a tabular-nums column.
    expect(formatBDT(-150).charCodeAt(0)).toBe(0x2212);
  });

  it('forces or drops the sign on request', () => {
    expect(formatBDT(500, { sign: 'always' })).toBe('+৳500');
    expect(formatBDT(-500, { sign: 'always' })).toBe('−৳500');
    expect(formatBDT(-500, { sign: 'never' })).toBe('৳500');
  });

  it('never invents paisa', () => {
    expect(formatBDT(1250.6)).toBe('৳1,250');
    expect(formatAmount(1250.6)).toBe('1,250');
  });
});

describe('parseAmount', () => {
  it('reads what an operator types', () => {
    expect(parseAmount('500')).toBe(500);
    expect(parseAmount(' 1,250 ')).toBe(1250);
    expect(parseAmount('৳1,250')).toBe(1250);
    expect(parseAmount('−150')).toBe(-150);
  });

  it('refuses anything that is not whole taka', () => {
    expect(parseAmount('')).toBeNull();
    expect(parseAmount('12.50')).toBeNull();
    expect(parseAmount('abc')).toBeNull();
    expect(parseAmount('1,2 3x')).toBeNull();
  });
});

describe('booking bill box', () => {
  it('is blocks × rate + package fee (ARCHITECTURE §5.11)', () => {
    expect(playAmount(4, 120)).toBe(480);
    expect(bookingTotal(4, 120, 100)).toBe(580);
    expect(formatBDT(bookingTotal(4, 120, 100))).toBe('৳580');
  });

  it('treats no blocks as no play time, never as negative', () => {
    expect(bookingTotal(0, 120, 100)).toBe(100);
    expect(bookingTotal(-3, 120, 100)).toBe(100);
  });
});
