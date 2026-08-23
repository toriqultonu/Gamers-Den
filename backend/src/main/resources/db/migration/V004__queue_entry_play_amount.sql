-- V004__queue_entry_play_amount.sql — the price snapshot a queue token is sold at (TASK B16).
--
-- ADDITIVE, and the one column B16 adds beyond the docs' DDL (backend/ARCHITECTURE.md §4.2:
-- "subsequent migrations numbered sequentially; additive-only during MVP").
--
-- Why it is needed. docs/bookings.md §5 gives `bookings` a `play_amount` snapshot but gives
-- `queue_entries` none, and a walk-up play ticket has no booking row to borrow one from. Two
-- invariants need that figure after the sale:
--
--   §5.9  seating a token inserts prepaid `session_blocks`, and `session_blocks.price NOT NULL`
--         is a snapshot of what the block was sold at;
--   §5.11 a no-show refund hands back what was taken, not what the rate card says today.
--
-- Deriving either from the live rate card at seat time would let a PUT /pricing between sale and
-- seat change a price that has already been paid — exactly what the snapshot rule exists to stop,
-- and in the refund case it is a money bug (RefundService caps at the transaction's bucket, so an
-- inflated figure is refused outright and a deflated one short-changes the customer).
--
-- DEFAULT 0 covers the BOOKING-source rows B15 already wrote: their money lives on the booking,
-- and check-in now copies bookings.play_amount across so both sources answer the same way.

ALTER TABLE queue_entries
  ADD COLUMN play_amount INT NOT NULL DEFAULT 0 CHECK (play_amount >= 0);

COMMENT ON COLUMN queue_entries.play_amount IS
  'blocks x the console block rate at the moment of sale — the snapshot prepaid session_blocks are '
  'born at and the amount a no-show refund hands back';

-- Backfill the tokens B15 issued from the bookings that paid for them, so the column is honest
-- about every row rather than only about the ones written from here on.
UPDATE queue_entries q
   SET play_amount = b.play_amount
  FROM bookings b
 WHERE q.booking_id = b.id
   AND q.play_amount = 0;
