-- Dev-profile demo data (TASK B22).
--
-- NOT a migration. The venue's schema and the rows it cannot boot without ship in V001/V003;
-- this is the furniture a developer needs on a fresh database to open a screen and see something
-- — a floor, a menu, two members, an open till, a booking that has been paid for and a walk-up
-- token still waiting for a console. It runs only under the `dev` profile and only when the floor
-- is empty, so it can never touch a venue or the cloud mirror, and re-running dev twice does not
-- double the furniture.
--
-- Written straight through SQL rather than through the services on purpose: a seed has no
-- operator, no JWT and no shift to post against, and half the services quite rightly refuse to
-- work without one. For the same reason it writes no sync_outbox ops — an op is the record of a
-- change the venue made, and nobody made these.

DO $$
DECLARE
  admin_id   BIGINT;
  ps5_one    BIGINT;
  ps5_two    BIGINT;
  rifat      BIGINT;
  nafis      BIGINT;
  shift_id   BIGINT;
  booking_tx BIGINT;
  ticket_tx  BIGINT;
  booking_id BIGINT;
  session_id BIGINT;
  today      DATE := (now() AT TIME ZONE 'Asia/Dhaka')::date;
BEGIN
  IF EXISTS (SELECT 1 FROM stations) THEN
    RAISE NOTICE 'demo seed skipped — the floor already has stations';
    RETURN;
  END IF;

  SELECT id INTO admin_id FROM staff WHERE name = 'Admin';

  -- Staff to sign in as. PIN 1234 for all three, same BCrypt hash as the V001 Admin: this is dev
  -- data and the venue's own PINs are set in S12, never here.
  INSERT INTO staff (name, role, pin_hash, avatar_color) VALUES
    ('Ayesha', 'MANAGER', '$2a$10$vQw5cb3Q8ayaEYS3AhxxLubAXCM1MYh262q9ClSEUZEySpBRTe7M6', '#2563eb'),
    ('Rafi',   'CASHIER', '$2a$10$vQw5cb3Q8ayaEYS3AhxxLubAXCM1MYh262q9ClSEUZEySpBRTe7M6', '#16a34a');

  -- The floor.
  INSERT INTO stations (name, console_type) VALUES ('PS5-01', 'PS5') RETURNING id INTO ps5_one;
  INSERT INTO stations (name, console_type) VALUES ('PS5-02', 'PS5') RETURNING id INTO ps5_two;
  INSERT INTO stations (name, console_type) VALUES ('PS5-03', 'PS5');
  INSERT INTO stations (name, console_type) VALUES ('PS4-01', 'PS4');
  INSERT INTO stations (name, console_type) VALUES ('PS4-02', 'PS4');

  -- The menu, with one line already under its reorder point so the stock watchlist has something
  -- to show on S2.
  INSERT INTO items (name, category, price, stock, reorder_at) VALUES
    ('Coca-Cola 250ml', 'BEVERAGE', 30,  48, 12),
    ('Mineral Water',   'BEVERAGE', 20,  60, 12),
    ('Pran Chips',      'SNACK',    25,   9, 10),
    ('Chicken Burger',  'FOOD',    180,  20,  5),
    ('Controller Hire', 'EXTRAS',   50, 100,  0);

  INSERT INTO members (name, phone, preferred_console, wallet, points)
    VALUES ('Rifat Hasan', '01711000001', 'PS5', 500, 120) RETURNING id INTO rifat;
  INSERT INTO members (name, phone, preferred_console, wallet, points)
    VALUES ('Nafis Iqbal', '01711000002', 'PS4',   0,  35) RETURNING id INTO nafis;

  -- An open till on T1, so the money routes work the moment a developer signs in.
  INSERT INTO shifts (staff_id, terminal, opening_float)
    VALUES (admin_id, 'T1', 2000) RETURNING id INTO shift_id;

  -- Pre-booking settings: the V003 defaults, restated so the seed is explicit about the venue it
  -- is describing — on, a 100 BDT package fee, cancellable up to 2 hours before the slot.
  UPDATE booking_settings SET enabled = TRUE, package_fee = 100, cancel_cutoff_hours = 2;

  -- A PAID booking for tomorrow evening: 2 x 30 min on PS5 at the plain rate (80) plus the
  -- package fee, paid in cash, exactly as POST /bookings would have written it.
  INSERT INTO transactions (public_id, member_id, shift_id, staff_id, booking_amount, total_due)
    VALUES ('GD-DEMO-001', rifat, shift_id, admin_id, 260, 260) RETURNING id INTO booking_tx;
  INSERT INTO payment_splits (tx_id, method, amount) VALUES (booking_tx, 'CASH', 260);
  INSERT INTO bookings (station_id, member_id, name, phone, start_at, blocks, play_amount,
                        package_fee, cutoff_hours, tx_id, status)
    VALUES (ps5_one, rifat, 'Rifat Hasan', '01711000001',
            (today + 1)::timestamptz + interval '19 hours', 2, 160, 100, 2, booking_tx, 'PAID')
    RETURNING id INTO booking_id;

  -- A walk-up play ticket still WAITING on the rail: 1 x 30 min on PS4 at 50, TOKEN #01 of today.
  INSERT INTO transactions (public_id, shift_id, staff_id, booking_amount, total_due)
    VALUES ('GD-DEMO-002', shift_id, admin_id, 50, 50) RETURNING id INTO ticket_tx;
  INSERT INTO payment_splits (tx_id, method, amount) VALUES (ticket_tx, 'CASH', 50);
  INSERT INTO queue_entries (token_date, token_no, source, tx_id, player_name, console_type,
                             blocks, play_amount, status)
    VALUES (today, 1, 'PLAY_TICKET', ticket_tx, 'Tanvir Ahmed', 'PS4', 1, 50, 'WAITING');
  -- The counter has handed out #01, so the next token of the day is #02.
  INSERT INTO token_seq (token_date, next_no) VALUES (today, 2)
    ON CONFLICT (token_date) DO UPDATE SET next_no = GREATEST(token_seq.next_no, 2);

  -- A running walk-in on PS5-02 with an hour bought and half of it played, so the Floor has a
  -- live countdown and GET /sessions/{id}/bill has something to add up.
  INSERT INTO sessions (station_id, member_id, shift_id, state, consumed_sec, running_since)
    VALUES (ps5_two, nafis, shift_id, 'RUNNING', 1800, now())
    RETURNING id INTO session_id;
  INSERT INTO session_blocks (session_id, price) VALUES (session_id, 80), (session_id, 80);

  RAISE NOTICE 'demo seed loaded: 5 stations, 5 items, 2 members, booking % and TOKEN #01', booking_id;
END $$;
