/**
 * Class-name joiner for the primitives.
 *
 * `cva`'s own `cx` is already a clsx-compatible joiner, so there is nothing to
 * hand-roll here — this module only gives the primitives one import path and
 * keeps `class-variance-authority` an implementation detail.
 */
export { cx as cn } from 'class-variance-authority';
export type { VariantProps } from 'class-variance-authority';
