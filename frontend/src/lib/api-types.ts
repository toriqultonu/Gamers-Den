/**
 * GENERATED FILE — DO NOT EDIT.
 *
 * Cut from the backend's OpenAPI document (`/v3/api-docs`) by
 * `npm run types:gen`. `npm run types:check` fails CI when this file or
 * `openapi.json` drifts from the live backend — regenerate, never hand-patch.
 */

export interface paths {
    "/api/v1/alerts": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The operator feed
         * @description Newest first: cash discrepancies at a shift close, print jobs that gave up, and items that crossed their reorder point. unread=true is what the bell badge counts.
         */
        get: operations["feed"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/alerts/{id}/read": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Mark one alert read
         * @description Idempotent by nature — an alert already read stays read, and says so.
         */
        post: operations["read_1"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/alerts/read-all": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Clear the bell
         * @description Marks every unread alert read and answers with what is left — the same list GET /alerts gives.
         */
        post: operations["readAll"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/login": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Sign in with staff id + 4-digit PIN
         * @description Returns a 15-minute access token and sets the 12-hour rotating refresh cookie. 401 on a wrong PIN, 423 LOCKED_PIN after 5 consecutive failures.
         */
        post: operations["login"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/logout": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Revoke the refresh token and clear the cookie */
        post: operations["logout"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/refresh": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Rotate the refresh cookie for a fresh access token
         * @description The presented cookie is spent; replaying it revokes the whole session family and answers 401.
         */
        post: operations["refresh"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/booking-settings": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** The pre-booking flag, package fee and cancellation window */
        get: operations["get_2"];
        /**
         * Set the pre-booking settings (Admin)
         * @description Every field optional; omitted fields keep their stored value. New bookings only — existing bookings keep the fee and cutoff they were sold under. Switching enabled off refuses new bookings with 409 PREBOOKING_DISABLED; the ones already paid for still check in and cancel.
         */
        put: operations["update_4"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/bookings": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The Upcoming or History tab
         * @description upcoming = PAID, soonest first; history = ARRIVED, USED and CANCELLED, most recent slot first. Rows on the Upcoming tab carry overlapping=true when another live booking shares their console and their time.
         */
        get: operations["list_7"];
        put?: never;
        /**
         * Take payment and hold a slot
         * @description One database transaction: the transaction snapshot with its booking_amount, the tender, the booking row and the print job carrying the P1 receipt and the P7 confirmation. The play total is blocks x the console's rate at the booked time and the package fee comes from /booking-settings; both are snapshotted onto the booking, so later edits to either reach new bookings only. 409 PREBOOKING_DISABLED when the feature is off, SPLIT_MISMATCH when the tender does not equal what is due, PAYMENT_REF_REQUIRED on a bKash/Nagad payment with no TrxID; each leaves nothing written. An overlap with another booking on the same console is returned as a warning, not refused.
         */
        post: operations["create_5"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/bookings/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** One booking */
        get: operations["get_9"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/bookings/{id}/cancel": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Cancel a booking and refund it in full
         * @description Only while PAID and while now <= startAt - the booking's own cutoffHours snapshot; the boundary itself still cancels. Writes a full negative transaction against the sale, posted to the shift doing the cancelling. 409 CANCEL_CUTOFF_PASSED inside the window, ALREADY_CHECKED_IN once the customer has arrived — that money goes back through a Manager+ void of the transaction instead.
         */
        post: operations["cancel_1"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/bookings/{id}/check-in": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Check in and print the token
         * @description Assigns the next daily queue token off the row-locked token_seq, writes its queue entry, moves the booking to ARRIVED and queues the P6 stub — one transaction. The token is shared with walk-up play tickets and restarts at venue midnight. Works while pre-booking is switched off: a booking already paid for stays serviceable. 409 ALREADY_CHECKED_IN on a second tap.
         */
        post: operations["checkIn_1"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/carts": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Open a cart
         * @description 201 with a new cart. A session has exactly one cart (carts.session_id is UNIQUE), so asking again for a seat that already has one returns it with 200 instead of failing.
         */
        post: operations["open_2"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/carts/{id}/lines": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * Set one cart line
         * @description qty 0 removes the line. A new line snapshots the item's price; changing the quantity keeps that snapshot. 409 OUT_OF_STOCK when the request exceeds the shelf minus what other open carts already hold.
         */
        put: operations["putLine"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/events": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Subscribe to live updates
         * @description Server-sent events: station-update (sessions and match timers), queue-update, booking-update, tournament-update, alert, printer-status and sync-status. Each payload is the shape of the GET it mirrors, so a handler can write it straight into the cache the polling fallback fills. Events are emitted after the transaction that caused them commits, so nothing arrives for work that rolled back. The stream is closed periodically by the server — reconnect, and poll every 10 s meanwhile.
         */
        get: operations["subscribe"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/expenses": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * A shift's petty cash
         * @description Newest first. Defaults to the open shift on this terminal; pass shiftId to read a closed one back.
         */
        get: operations["list_6"];
        put?: never;
        /**
         * Record a petty-cash payment
         * @description Posted to the open shift on this terminal, where it subtracts from the expected drawer. 409 when no shift is open — money cannot leave a till nobody has opened. voucher=true also queues the P4 slip.
         */
        post: operations["record"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/items": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The menu
         * @description Grouped by category, alphabetical inside it. The POS passes active=true; S10's editor passes nothing and sees retired rows too.
         */
        get: operations["list_5"];
        put?: never;
        /**
         * Add a menu item (Manager+)
         * @description 409 DUPLICATE_NAME when the name is taken. An opening stock lands as an INITIAL stock_movements row.
         */
        post: operations["create_4"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/items/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** One menu row with its derived stock state */
        get: operations["get_5"];
        put?: never;
        post?: never;
        /**
         * Take an item off the menu (Manager+)
         * @description Deleted outright while nothing points at it; once it has sales or stock history the row is deactivated instead, so the audit survives.
         */
        delete: operations["delete_2"];
        options?: never;
        head?: never;
        /**
         * Edit a menu item or correct its stock (Manager+)
         * @description stock is the absolute counted figure; the difference is audited as one signed MANUAL_ADJUST movement. 409 DUPLICATE_NAME on a taken name.
         */
        patch: operations["update_8"];
        trace?: never;
    };
    "/api/v1/me/prefs": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Read my profile prefs */
        get: operations["read"];
        /**
         * Set my avatar colour
         * @description null resets to the default swatch.
         */
        put: operations["update_3"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/members": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Search the directory
         * @description One box over name and phone: the name matches anywhere, the phone on digits so typed separators do not matter. No q lists everyone, by name.
         */
        get: operations["search"];
        put?: never;
        /**
         * Register a member
         * @description 409 DUPLICATE_PHONE when the number is already on file — the phone is compared normalised. An opening top-up is a separate wallet call.
         */
        post: operations["create_3"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/members/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * One member with their recent visits
         * @description Visits are the last sessions the member was attached to, newest first. The bookings list joins here in B15.
         */
        get: operations["get_8"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/members/{id}/wallet/redeem-points": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Convert points to wallet balance
         * @description Requires an Idempotency-Key. 1 point = ৳1; both ledgers and both columns move in one transaction. 409 INSUFFICIENT_POINTS.
         */
        post: operations["redeemPoints"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/members/{id}/wallet/topup": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Add money to a wallet
         * @description Requires an Idempotency-Key. Writes the TOPUP ledger row and the members.wallet total in one transaction.
         */
        post: operations["topup"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/overview": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Today's KPIs, the pre-sold stat, trends, stock watchlist and closes
         * @description Occupancy is this instant; the KPI tiles are the venue day; the trends are the last 30 venue days against the 30 before them. Pre-sold is money taken for play not yet delivered — PAID bookings plus WAITING play tickets. Nothing is stored; everything is folded per request.
         */
        get: operations["overview"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/payments": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Settle a session bill or a counter cart
         * @description One database transaction: the transaction snapshot and its tenders, the blocks it pays for, the stock it sells, the loyalty it moves, and the receipt print job. The session keeps running — paid blocks simply stop being billable. 409 SPLIT_MISMATCH when the tenders do not equal what is due, WALLET_INSUFFICIENT past the member's balance, PAYMENT_REF_REQUIRED on a bKash/Nagad row with no TrxID, TOURNAMENT_FULL / TOURNAMENT_NOT_OPEN on an entry that cannot be registered; each of them leaves nothing written. tournamentEntries[] come back as entryTokens[], one QR per entry, printed as P5 stubs on the same receipt. playTickets[] come back as queueTokens[], one daily token per ticket, printed as P6 stubs on the same receipt and entered in the play queue as WAITING — they are sellable while every console is busy, which is the whole point of the queue.
         */
        post: operations["settle"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/payments/{id}/void": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Reverse a settled payment in full
         * @description Manager+, and only within the shift that took the money. Writes a negative reversal transaction with negated tenders, releases the blocks it paid for back to billable, puts the stock back with VOID movements, revokes any play-queue token it sold that is still waiting, and hands the loyalty back — one transaction, like the settle it undoes. The original row is never edited, only flagged with its reason.
         */
        post: operations["voidPayment"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/play-queue": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Who plays next
         * @description Every WAITING token in counter order, then today's SEATED ones as history. Waiting tokens are not filtered to today: one issued yesterday and never seated keeps working and keeps its place, carrying its own tokenDate — the entry id is the key, not the number, which restarts at venue midnight.
         */
        get: operations["rail"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/play-queue/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        /**
         * Refund and remove a no-show
         * @description Manager+. One database transaction: a negative transaction against the sale, for the amount the token was sold at, and the token flipped to REFUNDED. The row is kept — the refund hangs off it — and the rail simply stops listing it. Walk-up tickets only: a checked-in booking's token is refunded by voiding its transaction, which revokes the token with it. 409 CONFLICT on a token already seated or already refunded.
         */
        delete: operations["remove"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/play-queue/{id}/seat": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Seat a waiting token on a console
         * @description One database transaction: the session, its prepaid session_blocks born carrying the original sale's paid_tx_id, the token to SEATED, and — when the token came from a pre-booking — the booking to USED. The clock starts when staff press start, and extra time is ordinary billable +30 blocks. Any waiting token may be seated, not just the first: the customer chooses. 409 CONSOLE_TYPE_MISMATCH on the wrong console type, STATION_BUSY on a taken seat, STATION_RESERVED while a tournament holds it.
         */
        post: operations["seat"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/play-tickets": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Sell one prepaid play-queue token
         * @description The standalone alias for POST /payments playTickets[]. One database transaction: the transaction snapshot with its booking_amount, the tender, the queue entry holding the next daily token, and the print job carrying the P1 receipt and the P6 stub. Sellable while every console is busy — that is what the queue is for. 409 PAYMENT_REF_REQUIRED on a bKash/Nagad sale with no TrxID; 400 on a console type the rate card does not know.
         */
        post: operations["sell"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/pricing": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** The rate card, one entry per console type */
        get: operations["list"];
        /**
         * Set rates for one or more console types (Admin)
         * @description Each entry names its consoleType. New blocks only — running sessions keep the prices they purchased.
         */
        put: operations["update_1"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/pricing/{consoleType}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** One console's rates */
        get: operations["get_1"];
        /** Set one console's rates (Admin) */
        put: operations["update_2"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/print-jobs/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * One ticket's queue state
         * @description QUEUED, PRINTING, DONE or FAILED, with the attempt count, the device, the operator, and — on a reprint — its reason and the original job. A FAILED job carries which failure it was (PAPER_OUT, COVER_OPEN, OFFLINE, MID_PRINT), so S11 names the thing to fix.
         */
        get: operations["get_7"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/print-jobs/{id}/render": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The stored 48-column render
         * @description The preview S11 draws, read back from the job — never recomputed. It was produced by the same pass that produced the bytes on the paper, so what is on screen is what came out of the printer.
         */
        get: operations["render"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/print-jobs/{id}/reprint": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Print it again, on the record
         * @description A new job carrying the original's stored bytes under the reprint band, with the reason recorded and the original linked. The reason is required — 400 VALIDATION_FAILED without it. Reprinting another operator's ticket needs Manager+ (api-contract.md §1, "Void/reprint others' transactions").
         */
        post: operations["reprint"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/print-jobs/{id}/retry": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Re-queue a failed ticket, same bytes
         * @description The stored bytes go back to the printer unchanged — not a fresh render — so the reprinted ticket is byte-identical to the one that failed, including after a mid-print failure where half of it is already on paper. The attempt count keeps climbing rather than resetting. 409 CONFLICT on a job that is not FAILED: a QUEUED job is already going to print, and a DONE one needs a reprint reason.
         */
        post: operations["retry"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/printers": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Attached printers and their live status
         * @description Default first. Each row's status is polled from the device while answering — ONLINE, OFFLINE, OUT_OF_PAPER or COVER_OPEN — rather than read from a cache, because the answer is only useful if it is current.
         */
        get: operations["list_8"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/printers/{printerId}/test": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Queue a test ticket on this printer
         * @description An ordinary print job — it queues, gets claimed, is attempted up to three times and ends DONE or FAILED like any receipt, so what it proves is the whole path and not just the cable. 404 on an id nothing answers to.
         */
        post: operations["test"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/printers/default": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * Choose the printer the venue prints on
         * @description Admin, as terminal configuration is (api-contract.md §1). The id must be one GET /printers listed — 404 otherwise. The choice holds for this running process; the venue's standing default lives in configuration (gamersden.printing.default-device), because the printer model is still an OPEN FLAG and no schema document gives printers a table.
         */
        put: operations["setDefault"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/reports": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * KPIs, trends, utilisation, busiest hours, top sellers and bookings
         * @description Every figure is folded from grouped reads at request time — nothing is stored, so a void or refund shows up immediately. Days are venue days (Asia/Dhaka), both bounds inclusive; the default range is the last 14 days. 400 when to is before from or the range is longer than a year.
         */
        get: operations["report"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sessions": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Sessions on the floor
         * @description active=true (the default) lists live seats; active=false lists closed sessions, most recently ended first.
         */
        get: operations["list_4"];
        put?: never;
        /**
         * Seat a customer
         * @description Opens a session with no time on it. With bookingId or queueEntryId the token's prepaid blocks are loaded as already paid and the token is consumed in the same transaction. 409 STATION_BUSY, STATION_RESERVED, CONSOLE_TYPE_MISMATCH.
         */
        post: operations["open_1"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sessions/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** One session with its server-derived clock and balance */
        get: operations["get_6"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sessions/{id}/bill": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * What this session owes right now
         * @description Gaming counts unbilled blocks only at their snapshot rates — prepaid blocks and blocks settled mid-session are already paid for and show as prepaidCredit instead of a charge. F&B comes from the unsettled cart, tournament entry fees from the session's registrations, and pointsRedeemable is min(member points, netTotal). Nothing here is stored.
         */
        get: operations["bill"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sessions/{id}/blocks": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Buy or return one 30-minute block
         * @description Requires an Idempotency-Key. +1 snapshots the current rate (morning window included) onto the new block; -1 returns the newest block that is neither paid for nor played, else 409 BLOCKS_CONSUMED.
         */
        post: operations["blocks"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sessions/{id}/clock": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Start, pause or resume the clock
         * @description START from OPEN, PAUSE from RUNNING, RESUME from PAUSED — anything else is 409 CONFLICT. Starting or resuming with no time left is 409 NO_BLOCKS.
         */
        post: operations["clock"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sessions/{id}/end": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * End the session
         * @description 409 SESSION_HAS_BALANCE while net outstanding — unpaid blocks plus the unsettled cart — is above zero. Prepaid blocks count as settled.
         */
        post: operations["end"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/shifts": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Shift history
         * @description Newest first, open shift included. The drawer figures are the Z snapshots and are absent on a shift that is still open.
         */
        get: operations["history"];
        put?: never;
        /**
         * Open a shift on this terminal
         * @description 409 SHIFT_ALREADY_OPEN when one is already open here — the terminal, not the operator, is what a shift is unique per. The float is what is in the drawer before the first sale; the close counts against it.
         */
        post: operations["open"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/shifts/current/close": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Count the drawer and close the shift
         * @description One transaction: the Z figures snapshotted onto the shift, the P2 print job, an alert row when the count does not match, and the operator signed out of this terminal. A cashier may only close their own shift; Manager+ may close anyone's.
         */
        post: operations["close"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/shifts/current/x-report": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The interim read of this terminal's open shift
         * @description Takings by method x category — gaming, F&B, tournament entries and pre-bookings — the shift's petty cash, and the cash the drawer should hold right now. Nothing is stored: ask again after the next sale and the figures will have moved. print=true also queues the P3 job.
         */
        get: operations["xReport"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/staff": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** List staff (Admin) */
        get: operations["list_3"];
        put?: never;
        /**
         * Add a Manager or Cashier with a PIN (Admin)
         * @description 409 DUPLICATE_NAME when the name is taken.
         */
        post: operations["create_2"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/staff/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        /**
         * Remove a staff member (Admin)
         * @description Deactivates rather than deletes — shifts, sessions and transactions keep pointing at the row. 409 STAFF_ON_SHIFT while a shift is open.
         */
        delete: operations["delete_1"];
        options?: never;
        head?: never;
        /**
         * Edit a staff member (Admin)
         * @description 409 DUPLICATE_NAME on a taken name; a new PIN clears any lock and revokes that account's live refresh tokens.
         */
        patch: operations["update_7"];
        trace?: never;
    };
    "/api/v1/stations": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The Floor grid
         * @description Each station with its live session summary. A console held by a running tournament reads RESERVED, and carries its match countdown while one is being played on it. The checked-in arrival half arrives with B16 and is null until then.
         */
        get: operations["list_2"];
        put?: never;
        /**
         * Add a station (Admin)
         * @description 409 DUPLICATE_NAME when the name is taken.
         */
        post: operations["create_1"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/stations/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** One station card */
        get: operations["get_4"];
        put?: never;
        post?: never;
        /**
         * Remove a station (Admin)
         * @description 409 STATION_IN_USE while a session is live on it or any session history still points at it — put it under maintenance instead.
         */
        delete: operations["delete"];
        options?: never;
        head?: never;
        /**
         * Edit a station (Admin)
         * @description 409 DUPLICATE_NAME on a taken name; 409 STATION_IN_USE when the console type or the maintenance flag would move under a live session.
         */
        patch: operations["update_6"];
        trace?: never;
    };
    "/api/v1/sync/status": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Outbox state
         * @description SYNCED when nothing is pending, SYNCING when the venue is ahead of the cloud, OFFLINE when the last push attempt failed. The venue trades either way — the outbox drains on reconnect.
         */
        get: operations["status"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/terminal-settings": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * This terminal's settings
         * @description A terminal that has never been configured answers with the defaults: dark theme, default text size, Den Red, no background, sound on, 5-minute auto-lock, 1 receipt copy.
         */
        get: operations["get"];
        /**
         * Replace this terminal's settings (Admin)
         * @description The whole object; every field is required except loginBgImageId, which carries the id of the terminal's uploaded background or null to remove it.
         */
        put: operations["update"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/terminal-settings/login-bg": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Upload this terminal's login background (Admin)
         * @description PNG, JPEG or WebP, validated by its own bytes rather than by the part's Content-Type. Replaces whatever the terminal had; the new id is what GET /terminal-settings/login-bg/{imageId} is fetched by.
         */
        post: operations["uploadLoginBg"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/terminal-settings/login-bg/{imageId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Serve a login background
         * @description Public: the login screen renders it before sign-in. 404 once the background is removed or replaced.
         */
        get: operations["loginBg"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/tournament-entries/{id}/check-in": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Mark a player as arrived
         * @description The QR off the P5 stub, which has to match the entry it is presented against. 409 ALREADY_CHECKED_IN on a second scan.
         */
        post: operations["checkIn"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/tournaments": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Events still selling or being played
         * @description Soonest first. slotsLeft is what the POS Tournament category disables its card on.
         */
        get: operations["list_1"];
        put?: never;
        /**
         * Create an event (Manager+)
         * @description maxPlayers must be one of 4, 8, 16 or 32 — a perfect bracket has exactly N-1 matches and no byes. 409 DUPLICATE_NAME on a taken name.
         */
        post: operations["create"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/tournaments/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * One event with its entries, blocked consoles and bracket
         * @description bracket is empty until the event is drawn — before that the screen is the registered-player list. Every started match carries its own remainingSeconds, computed from the server clock.
         */
        get: operations["get_3"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        /**
         * Edit an event (Manager+)
         * @description Only while OPEN — 409 TOURNAMENT_NOT_OPEN otherwise. Once a ticket has been sold the entry fee is frozen and the cap cannot drop below the entries taken; both are 409 CONFLICT.
         */
        patch: operations["update_5"];
        trace?: never;
    };
    "/api/v1/tournaments/{id}/blocks": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * Hold consoles for an event (Manager+)
         * @description Replaces the whole allocation. While the event is OPEN or LIVE these consoles read RESERVED on the Floor and refuse walk-in sessions with 409 STATION_RESERVED; an empty list releases them.
         */
        put: operations["setBlocks"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/tournaments/{id}/bracket": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Draw the bracket now (Manager+)
         * @description For an event that never filled: the smallest power-of-two bracket that seats everybody who bought in, byes advancing the earliest seeds, and the event goes LIVE. An event that fills is drawn automatically by the sale that takes the last slot, so this is the undersubscribed case. 409 NOT_ENOUGH_PLAYERS under two players, 409 TOURNAMENT_NOT_OPEN once it is already live, done or called off.
         */
        post: operations["generateBracket"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/tournaments/{id}/cancel": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Call an event off and refund everyone (Manager+)
         * @description One transaction: status CANCELLED, every console released, and a negative refund transaction per originating sale, posted to the shift open on this terminal. Money goes back through the methods it came in by.
         */
        post: operations["cancel"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/tournaments/{id}/entries": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Sell one entry at the counter
         * @description The same settle POST /payments runs: one transaction writes the money, the entry with its seed and QR, and the receipt with its P5 stub. 409 TOURNAMENT_FULL past the cap, TOURNAMENT_NOT_OPEN once the bracket is live.
         */
        post: operations["sellEntry"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/tournaments/{id}/finance": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Revenue against what the consoles would have earned (Manager+)
         * @description 403 for a cashier token, and never embedded in a shared payload. revenue = entries x entryFee; netProfit = revenue - prizePool; opportunityCost = (N-1) x matchDurationMin/60 x avgHourlyRate of the allocated consoles; extraMargin = netProfit - opportunityCost.
         */
        get: operations["finance"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/tournaments/{id}/matches": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The match board, with console availability
         * @description pending=true narrows it to the cashier job board: matches with both players and no winner yet. The consoles come with it, each carrying why it is or is not free — "Allocated console busy with a walk-in session" is the case start would otherwise refuse without explanation.
         */
        get: operations["board"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/tournaments/{id}/matches/{mid}/extend": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Add time to a match in play
         * @description Minutes accumulate on the match; every countdown re-bases off the same read, so the board, the bracket tag, the "Now on" tile and the Floor card all move together. A match whose time is already up is the normal case. 409 CONFLICT on a match that has not been started or is already decided.
         */
        post: operations["extend"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/tournaments/{id}/matches/{mid}/start": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Put a match on a console
         * @description Takes the first allocated console that is neither hosting an unfinished match nor busy with a walk-in session, and stamps started_at — the countdown runs from there. 409 NO_FREE_CONSOLE when every allocated console is taken; the details list what each of them is doing.
         */
        post: operations["start"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/tournaments/{id}/matches/{mid}/winner": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Record the winner of a match
         * @description Any role for a match that has been started — that is execution. A match nobody started is a ruling and needs Manager+: a cashier gets the 403 envelope. The winner advances along next_match_id, and the response says which console their next match would take. Winning the final makes the champion, turns the event DONE and releases every console it held.
         */
        post: operations["recordWinner"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/tournaments/history": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Finished and called-off events, most recent first
         * @description The History tab: winners, prizes and entry counts by date. A finished event carries winnerEntryId and winnerName; a called-off one carries its cancelledReason instead.
         */
        get: operations["history_1"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
}
export type webhooks = Record<string, never>;
export interface components {
    schemas: {
        /** @description One row of the operator feed */
        Alert: {
            body?: string;
            /** Format: date-time */
            createdAt?: string;
            /** Format: int64 */
            id?: number;
            read?: boolean;
            title?: string;
            type?: string;
        };
        /** @description What a session owes right now — unbilled blocks, F&B, tournament entries, and the prepaid credit already covering it */
        Bill: {
            /** Format: int32 */
            billableBlocks?: number;
            /** Format: int32 */
            fnbDue?: number;
            /** Format: int32 */
            gamingDue?: number;
            /** Format: int32 */
            gamingValue?: number;
            lines?: components["schemas"]["BillLine"][];
            /** Format: int64 */
            memberId?: number;
            memberName?: string;
            /** Format: int32 */
            memberPoints?: number;
            /** Format: int32 */
            memberWallet?: number;
            /** Format: int32 */
            netTotal?: number;
            /** Format: int32 */
            pointsRedeemable?: number;
            /** Format: int32 */
            prepaidBlocks?: number;
            /** Format: int32 */
            prepaidCredit?: number;
            /** Format: date-time */
            serverTime?: string;
            /** Format: int64 */
            sessionId?: number;
            sessionState?: string;
            settled?: boolean;
            /** Format: int64 */
            stationId?: number;
            /** Format: int32 */
            tournamentDue?: number;
        };
        BillLine: {
            /** Format: int32 */
            amount?: number;
            /** @enum {string} */
            kind?: "GAMING" | "FNB" | "TOURNAMENT";
            label?: string;
            /** Format: int32 */
            qty?: number;
            /** Format: int64 */
            refId?: number;
            /** Format: int32 */
            unitPrice?: number;
        };
        BlocksRequest: {
            /**
             * Format: int32
             * @description +1 buys a 30-minute block at the current rate, -1 returns the newest unpaid, unplayed one
             * @enum {integer}
             */
            delta: "-1" | "1";
        };
        /** @description A prepaid slot */
        Booking: {
            /** Format: int32 */
            blocks?: number;
            cancellable?: boolean;
            /** Format: date-time */
            cancellableUntil?: string;
            consoleType?: string;
            /** Format: date-time */
            createdAt?: string;
            /** Format: int32 */
            cutoffHours?: number;
            /** Format: date-time */
            endAt?: string;
            /** Format: int64 */
            id?: number;
            /** Format: int64 */
            memberId?: number;
            name?: string;
            overlapping?: boolean;
            /** Format: int32 */
            packageFee?: number;
            phone?: string;
            /** Format: int32 */
            playAmount?: number;
            /** Format: int64 */
            queueEntryId?: number;
            /** Format: int64 */
            refundTransactionId?: number;
            /** Format: date-time */
            startAt?: string;
            /** Format: int64 */
            stationId?: number;
            stationName?: string;
            status?: string;
            /** Format: date */
            tokenDate?: string;
            /** Format: int32 */
            tokenNo?: number;
            /** Format: int32 */
            total?: number;
            /** Format: int64 */
            transactionId?: number;
        };
        /** @description A cancelled booking and its refund */
        BookingCancelled: {
            booking?: components["schemas"]["Booking"];
            /** Format: int32 */
            refundAmount?: number;
            refundPublicId?: string;
            /** Format: int64 */
            refundTransactionId?: number;
        };
        /** @description A booking checked in, holding a queue token */
        BookingCheckedIn: {
            booking?: components["schemas"]["Booking"];
            /** Format: int64 */
            printJobId?: number;
            token?: components["schemas"]["QueueToken"];
        };
        /** @description A booking, paid for and held */
        BookingCreated: {
            booking?: components["schemas"]["Booking"];
            overlappingBookingIds?: number[];
            /** Format: int64 */
            printJobId?: number;
            publicId?: string;
            /** Format: int64 */
            transactionId?: number;
        };
        /** @description Pre-booking feature flag, package fee and cancellation window. Changes apply to NEW bookings only. */
        BookingSettings: {
            /** Format: int32 */
            cancelCutoffHours?: number;
            enabled?: boolean;
            /** Format: int32 */
            packageFee?: number;
            /** Format: date-time */
            updatedAt?: string;
            /** Format: int64 */
            updatedBy?: number;
        };
        CancelBookingRequest: {
            reason?: string;
        };
        CancelTournamentRequest: {
            reason?: string;
        };
        Cart: {
            /** Format: date-time */
            createdAt?: string;
            /** Format: int64 */
            id?: number;
            lines?: components["schemas"]["CartLine"][];
            /** Format: int64 */
            sessionId?: number;
            settled?: boolean;
            /** Format: int32 */
            total?: number;
            /** @enum {string} */
            type?: "COUNTER" | "SESSION";
        };
        CartLine: {
            /** @enum {string} */
            category?: "BEVERAGE" | "FOOD" | "SNACK" | "EXTRAS";
            /** Format: int64 */
            itemId?: number;
            /** Format: int32 */
            lineTotal?: number;
            name?: string;
            /** Format: int32 */
            qty?: number;
            /** Format: int32 */
            unitPrice?: number;
        };
        CartLineRequest: {
            /** Format: int64 */
            itemId: number;
            /** Format: int32 */
            qty: number;
        };
        CheckInRequest: {
            qrToken: string;
        };
        ClockRequest: {
            /** @enum {string} */
            action: "START" | "PAUSE" | "RESUME";
        };
        /** @description Count the drawer and close the shift */
        CloseShiftRequest: {
            /** Format: int32 */
            countedCash: number;
            handoverNote?: string;
        };
        ConsoleAvailability: {
            available?: boolean;
            /**
             * Format: int64
             * @description The match occupying it, if any
             */
            matchId?: number;
            note?: string;
            /** @enum {string} */
            state?: "FREE" | "WALK_IN_SESSION" | "MATCH_IN_PLAY" | "MAINTENANCE";
            /** Format: int64 */
            stationId?: number;
            stationName?: string;
        };
        /** @description Take payment and hold a slot */
        CreateBookingRequest: {
            /** Format: int32 */
            blocks?: number;
            /** Format: int64 */
            memberId?: number;
            method: string;
            name?: string;
            paymentRef?: string;
            phone?: string;
            /** Format: date-time */
            startAt: string;
            /** Format: int64 */
            stationId: number;
        };
        CreateCartRequest: {
            /** Format: int64 */
            sessionId?: number;
            /** @enum {string} */
            type: "COUNTER" | "SESSION";
        };
        /** @description Record a petty-cash payment against the open shift */
        CreateExpenseRequest: {
            /** Format: int32 */
            amount: number;
            /** @enum {string} */
            category: "SUPPLIES" | "UTILITIES" | "REPAIRS" | "STAFF" | "OTHER";
            description: string;
        };
        CreateItemRequest: {
            /** @enum {string} */
            category: "BEVERAGE" | "FOOD" | "SNACK" | "EXTRAS";
            name: string;
            /** Format: int32 */
            price: number;
            /** Format: int32 */
            reorderAt?: number;
            /** Format: int32 */
            stock?: number;
        };
        CreateMemberRequest: {
            /** @description Free-text favourites shown on the member card */
            games?: string[];
            name: string;
            phone: string;
            /** @description PS5 or PS4 — what the desk seats them on by default */
            preferredConsole?: string;
        };
        CreateSessionRequest: {
            /**
             * Format: int64
             * @description Seat a checked-in booking — loads its prepaid blocks as paid
             */
            bookingId?: number;
            /**
             * Format: int64
             * @description Attach a member to the seat for points and wallet at settle
             */
            memberId?: number;
            /**
             * Format: int64
             * @description Seat a play-queue token — loads its prepaid blocks as paid
             */
            queueEntryId?: number;
            /** Format: int64 */
            stationId: number;
        };
        CreateStaffRequest: {
            name: string;
            pin: string;
            /** @enum {string} */
            role: "MANAGER" | "CASHIER";
        };
        CreateStationRequest: {
            /** @enum {string} */
            consoleType: "PS5" | "PS4";
            name: string;
        };
        CreateTournamentRequest: {
            /** @enum {string} */
            cadence: "WEEKLY" | "MONTHLY" | "ONE_OFF";
            /** Format: int32 */
            entryFee: number;
            game: string;
            /** Format: int32 */
            matchDurationMin: number;
            /** Format: int32 */
            maxPlayers: number;
            name: string;
            /** Format: int32 */
            prizePool: number;
            /** Format: date-time */
            scheduledAt: string;
        };
        /** @description Which attached printer to print on */
        DefaultPrinterRequest: {
            printerId: string;
        };
        /** @description A tournament entry sold at the counter */
        EntrySold: {
            /** Format: int64 */
            entryId?: number;
            /** Format: int64 */
            printJobId?: number;
            publicId?: string;
            qrToken?: string;
            /** Format: int32 */
            seed?: number;
            /** Format: int64 */
            transactionId?: number;
        };
        EntrySplit: {
            /** Format: int32 */
            amount?: number;
            method: string;
            paymentRef?: string;
        };
        /** @description A petty-cash payment posted to the shift that made it */
        Expense: {
            /** Format: int32 */
            amount?: number;
            category?: string;
            /** Format: date-time */
            createdAt?: string;
            description?: string;
            /** Format: int64 */
            id?: number;
            /** Format: int64 */
            printJobId?: number;
            /** Format: int64 */
            shiftId?: number;
            /** Format: int64 */
            staffId?: number;
        };
        ExtendMatchRequest: {
            /**
             * Format: int32
             * @description Minutes to add on top of whatever has already been added
             * @example 5
             */
            minutes: number;
        };
        Item: {
            active?: boolean;
            /** Format: int32 */
            available?: number;
            /** @enum {string} */
            category?: "BEVERAGE" | "FOOD" | "SNACK" | "EXTRAS";
            /** Format: int64 */
            id?: number;
            lowStock?: boolean;
            name?: string;
            outOfStock?: boolean;
            /** Format: int32 */
            price?: number;
            /** Format: int32 */
            reorderAt?: number;
            /** Format: int32 */
            stock?: number;
        };
        LoginBgUploaded: {
            loginBgImageId?: string;
        };
        LoginRequest: {
            /**
             * @description 4-digit PIN; never logged
             * @example 1234
             */
            pin: string;
            /**
             * Format: int64
             * @description staff.id chosen on the login screen
             * @example 1
             */
            staffId: number;
            /**
             * @description POS terminal identifier
             * @example T1
             */
            terminal: string;
        };
        MatchBoard: {
            /** @description Every console blocked for this event, in the order match start picks from */
            consoles?: components["schemas"]["ConsoleAvailability"][];
            /** Format: int32 */
            freeConsoles?: number;
            /** @description Drawing order; with pending=true, only matches with both players and no winner yet */
            matches?: components["schemas"]["TournamentMatch"][];
        };
        MatchDecision: {
            bracket?: components["schemas"]["TournamentMatch"][];
            champion?: boolean;
            entries?: components["schemas"]["TournamentEntry"][];
            /** Format: int64 */
            nextMatchId?: number;
            stationIds?: number[];
            /** Format: int64 */
            suggestedStationId?: number;
            tournament?: components["schemas"]["Tournament"];
        };
        /** @description A registered customer with their wallet and points balance */
        Member: {
            /** Format: date-time */
            createdAt?: string;
            games?: string[];
            /** Format: int64 */
            id?: number;
            name?: string;
            phone?: string;
            /** Format: int32 */
            points?: number;
            preferredConsole?: string;
            /** Format: int32 */
            wallet?: number;
        };
        /** @description A booking the member holds or has held */
        MemberBooking: {
            /** Format: int32 */
            blocks?: number;
            /** Format: int64 */
            bookingId?: number;
            /** Format: date-time */
            startAt?: string;
            /** Format: int64 */
            stationId?: number;
            stationName?: string;
            status?: string;
            /** Format: int32 */
            tokenNo?: number;
            /** Format: int32 */
            total?: number;
        };
        /** @description A member with their recent visits and bookings */
        MemberDetail: {
            bookings?: components["schemas"]["MemberBooking"][];
            /** Format: date-time */
            createdAt?: string;
            games?: string[];
            /** Format: int64 */
            id?: number;
            name?: string;
            phone?: string;
            /** Format: int32 */
            points?: number;
            preferredConsole?: string;
            visits?: components["schemas"]["MemberVisit"][];
            /** Format: int32 */
            wallet?: number;
        };
        /** @description A past or current session the member was attached to */
        MemberVisit: {
            /** Format: int32 */
            blocks?: number;
            consoleType?: string;
            /** Format: date-time */
            endedAt?: string;
            /** Format: int64 */
            playedSeconds?: number;
            /** Format: int64 */
            sessionId?: number;
            /** Format: date-time */
            startedAt?: string;
            state?: string;
            /** Format: int64 */
            stationId?: number;
            stationName?: string;
        };
        /** @description Open the caller's terminal for business */
        OpenShiftRequest: {
            /** Format: int32 */
            openingFloat: number;
        };
        /** @description S2's KPIs, pre-sold stat, trends, watchlist and closes */
        Overview: {
            byDayOfWeek?: components["schemas"]["OverviewWeekday"][];
            /** Format: date */
            date?: string;
            occupancy?: components["schemas"]["OverviewOccupancy"];
            preSold?: components["schemas"]["OverviewPreSold"];
            recentCloses?: components["schemas"]["OverviewShiftClose"][];
            revenue30Days?: components["schemas"]["OverviewTrend"];
            /** Format: date-time */
            serverTime?: string;
            stockWatchlist?: components["schemas"]["OverviewStockWatch"][];
            today?: components["schemas"]["ReportKpis"];
        };
        OverviewOccupancy: {
            /** Format: int32 */
            available?: number;
            /** Format: int32 */
            busy?: number;
            /** Format: int32 */
            maintenance?: number;
            /** Format: double */
            pct?: number;
            /** Format: int32 */
            stations?: number;
        };
        OverviewPreSold: {
            /** Format: int32 */
            amount?: number;
            /** Format: int32 */
            bookingAmount?: number;
            /** Format: int32 */
            bookingPackageFee?: number;
            /** Format: int32 */
            bookingPlayAmount?: number;
            /** Format: int32 */
            bookings?: number;
            /** Format: int32 */
            playTicketAmount?: number;
            /** Format: int32 */
            playTickets?: number;
        };
        OverviewShiftClose: {
            /** Format: date-time */
            closedAt?: string;
            /** Format: int32 */
            countedCash?: number;
            /** Format: int32 */
            discrepancy?: number;
            /** Format: int32 */
            expectedCash?: number;
            /** Format: date-time */
            openedAt?: string;
            /** Format: int32 */
            openingFloat?: number;
            /** Format: int64 */
            shiftId?: number;
            /** Format: int64 */
            staffId?: number;
            /** Format: int32 */
            takings?: number;
            terminal?: string;
        };
        OverviewStockWatch: {
            category?: string;
            /** Format: int64 */
            itemId?: number;
            name?: string;
            /** Format: int32 */
            reorderAt?: number;
            /** Format: int32 */
            stock?: number;
        };
        OverviewTrend: {
            days?: components["schemas"]["ReportTrendPoint"][];
            /** Format: int32 */
            previousRevenue?: number;
            /** Format: int32 */
            revenue?: number;
        };
        OverviewWeekday: {
            /** Format: int32 */
            average?: number;
            day?: string;
            /** Format: int32 */
            days?: number;
            /** Format: int32 */
            revenue?: number;
        };
        PageResponseMember: {
            content?: components["schemas"]["Member"][];
            /** Format: int32 */
            page?: number;
            /** Format: int32 */
            size?: number;
            /** Format: int64 */
            totalElements?: number;
            /** Format: int32 */
            totalPages?: number;
        };
        PageResponseShift: {
            content?: components["schemas"]["Shift"][];
            /** Format: int32 */
            page?: number;
            /** Format: int32 */
            size?: number;
            /** Format: int64 */
            totalElements?: number;
            /** Format: int32 */
            totalPages?: number;
        };
        PaymentSplit: {
            /** Format: int32 */
            amount?: number;
            /** @enum {string} */
            method: "CASH" | "BKASH" | "NAGAD" | "WALLET";
            paymentRef?: string;
        };
        PlayQueueToken: {
            /** Format: int32 */
            blocks?: number;
            consoleType?: string;
            playerName?: string;
            /** Format: int64 */
            queueEntryId?: number;
            /** Format: date */
            tokenDate?: string;
            /** Format: int32 */
            tokenNo?: number;
        };
        PlayTicketRequest: {
            /** Format: int32 */
            blocks?: number;
            consoleType: string;
            playerName?: string;
        };
        /** @description A prepaid play-queue token, paid for */
        PlayTicketSold: {
            /** Format: int32 */
            amount?: number;
            /** Format: int64 */
            printJobId?: number;
            publicId?: string;
            token?: components["schemas"]["PlayQueueToken"];
            /** Format: int64 */
            transactionId?: number;
        };
        PrefsRequest: {
            /** @example #ec3013 */
            avatarColor?: string | null;
        };
        PrefsResponse: {
            /** @example #ec3013 */
            avatarColor?: string | null;
        };
        Pricing: {
            /** @enum {string} */
            consoleType?: "PS5" | "PS4";
            /**
             * Format: int32
             * @description The block price right now — morning discount already applied
             */
            currentBlockPrice?: number;
            /** Format: int32 */
            morningDiscountPct?: number;
            morningEnd?: string;
            morningStart?: string;
            /** Format: int32 */
            perHalfHour?: number;
            /** Format: int32 */
            perHour?: number;
            /** Format: date-time */
            updatedAt?: string;
        };
        /** @description An attached printer and its live status */
        Printer: {
            id?: string;
            isDefault?: boolean;
            name?: string;
            status?: string;
        };
        /** @description One queued, printed or failed ticket */
        PrintJob: {
            /** Format: int32 */
            attempts?: number;
            /** Format: date-time */
            completedAt?: string;
            /** Format: date-time */
            createdAt?: string;
            device?: string;
            error?: string;
            /** Format: int64 */
            id?: number;
            isReprint?: boolean;
            /** Format: int64 */
            operatorId?: number;
            /** Format: int64 */
            originalJobId?: number;
            /** Format: int64 */
            refId?: number;
            reprintReason?: string;
            status?: string;
            type?: string;
        };
        /** @description The stored character-grid render of a print job */
        PrintRender: {
            /** Format: int32 */
            bytes?: number;
            /** Format: int32 */
            columns?: number;
            /** Format: int64 */
            id?: number;
            text?: string;
            type?: string;
        };
        /** @description One issued daily token */
        QueueEntry: {
            /** Format: int32 */
            blocks?: number;
            /** Format: int64 */
            bookingId?: number;
            consoleType?: string;
            /** Format: date-time */
            createdAt?: string;
            /** Format: int64 */
            id?: number;
            /** Format: int32 */
            playAmount?: number;
            playerName?: string;
            /** Format: int64 */
            sessionId?: number;
            source?: string;
            status?: string;
            /** Format: date */
            tokenDate?: string;
            /** Format: int32 */
            tokenNo?: number;
            /** Format: int64 */
            transactionId?: number;
        };
        /** @description A no-show refunded and taken out of the queue */
        QueueEntryRemoved: {
            entry?: components["schemas"]["QueueEntry"];
            refund?: components["schemas"]["QueueRefund"];
        };
        /** @description A token seated on a console */
        QueueEntrySeated: {
            entry?: components["schemas"]["QueueEntry"];
            session?: components["schemas"]["SeatedSession"];
        };
        QueueRefund: {
            /** Format: int32 */
            amount?: number;
            publicId?: string;
            /** Format: int64 */
            transactionId?: number;
        };
        QueueToken: {
            /** Format: int64 */
            queueEntryId?: number;
            /** Format: date */
            tokenDate?: string;
            /** Format: int32 */
            tokenNo?: number;
        };
        RecordWinnerRequest: {
            /**
             * Format: int64
             * @description One of the two entries playing the match
             */
            winnerEntryId: number;
        };
        RedeemPointsRequest: {
            /**
             * Format: int32
             * @description Points to convert; the wallet gains the same number of BDT
             */
            points: number;
        };
        /** @description S9's aggregates over a range of venue days */
        Report: {
            bookings?: components["schemas"]["ReportBookings"];
            busiestHours?: components["schemas"]["ReportHour"][];
            kpis?: components["schemas"]["ReportKpis"];
            range?: components["schemas"]["ReportRange"];
            /** Format: date-time */
            serverTime?: string;
            stationUtilisation?: components["schemas"]["ReportStationUtilisation"][];
            topSellers?: components["schemas"]["ReportTopSeller"][];
            /** Format: int64 */
            tradingSeconds?: number;
            trend?: components["schemas"]["ReportTrendPoint"][];
        };
        ReportBookings: {
            /** Format: int32 */
            arrived?: number;
            /** Format: int32 */
            booked?: number;
            /** Format: int32 */
            cancelled?: number;
            /** Format: int32 */
            expired?: number;
            /** Format: int32 */
            income?: number;
            /** Format: int32 */
            packageFeeIncome?: number;
            perDay?: components["schemas"]["ReportBookingsDay"][];
            /** Format: int32 */
            playIncome?: number;
            /** Format: double */
            showRatePct?: number;
            /** Format: int32 */
            sold?: number;
            /** Format: int32 */
            used?: number;
        };
        ReportBookingsDay: {
            /** Format: int32 */
            arrived?: number;
            /** Format: int32 */
            booked?: number;
            /** Format: int32 */
            cancelled?: number;
            /** Format: date */
            date?: string;
            /** Format: int32 */
            expired?: number;
            /** Format: int32 */
            used?: number;
        };
        ReportHour: {
            /** Format: double */
            avgStationsBusy?: number;
            /** Format: int64 */
            busySeconds?: number;
            /** Format: int32 */
            hour?: number;
            /** Format: int32 */
            revenue?: number;
            /** Format: int32 */
            sales?: number;
        };
        /** @description Takings, petty cash and net profit over a period */
        ReportKpis: {
            /** Format: int32 */
            avgTicket?: number;
            /** Format: int32 */
            booking?: number;
            /** Format: int32 */
            expenses?: number;
            /** Format: int32 */
            fnb?: number;
            /** Format: int32 */
            gaming?: number;
            /** Format: int32 */
            netProfit?: number;
            /** Format: int32 */
            pointsRedeemed?: number;
            /** Format: int32 */
            revenue?: number;
            /** Format: int32 */
            sales?: number;
            /** Format: int32 */
            sessions?: number;
            /** Format: int32 */
            tournament?: number;
            /** Format: int32 */
            transactions?: number;
        };
        ReportRange: {
            /** Format: int32 */
            days?: number;
            /** Format: date */
            from?: string;
            /** Format: date */
            to?: string;
        };
        ReportStationUtilisation: {
            /** Format: int64 */
            busySeconds?: number;
            consoleType?: string;
            name?: string;
            /** Format: int32 */
            sessions?: number;
            /** Format: int64 */
            stationId?: number;
            underMaintenance?: boolean;
            /** Format: double */
            utilisationPct?: number;
        };
        ReportTopSeller: {
            category?: string;
            /** Format: int64 */
            itemId?: number;
            name?: string;
            /** Format: int32 */
            revenue?: number;
            /** Format: int32 */
            units?: number;
        };
        /** @description One venue day of takings, expenses and profit */
        ReportTrendPoint: {
            /** Format: int32 */
            booking?: number;
            /** Format: date */
            date?: string;
            /** Format: int32 */
            expenses?: number;
            /** Format: int32 */
            fnb?: number;
            /** Format: int32 */
            gaming?: number;
            /** Format: int32 */
            netProfit?: number;
            /** Format: int32 */
            pointsRedeemed?: number;
            /** Format: int32 */
            revenue?: number;
            /** Format: int32 */
            sales?: number;
            /** Format: int32 */
            tournament?: number;
            /** Format: int32 */
            transactions?: number;
        };
        /** @description Why this ticket is being printed again */
        ReprintRequest: {
            /** @enum {string} */
            reason: "LOST" | "DAMAGED" | "CUSTOMER_COPY" | "DISPUTE";
        };
        SeatedSession: {
            /** Format: int32 */
            blocks?: number;
            /** Format: int64 */
            id?: number;
            /** Format: int32 */
            netOutstanding?: number;
            /** Format: int32 */
            paidBlocks?: number;
            /** Format: int64 */
            remainingSeconds?: number;
            state?: string;
            /** Format: int64 */
            stationId?: number;
            stationName?: string;
        };
        /** @description Which console to seat this token on */
        SeatQueueEntryRequest: {
            /** Format: int64 */
            stationId: number;
        };
        /** @description Sell one tournament entry at the counter */
        SellEntryRequest: {
            playerName?: string;
            splits: components["schemas"]["EntrySplit"][];
        };
        /** @description Sell one prepaid play-queue token */
        SellPlayTicketRequest: {
            /** Format: int32 */
            blocks?: number;
            consoleType: string;
            method: string;
            paymentRef?: string;
            playerName?: string;
        };
        /** @description A floor session with its server-derived clock and balance */
        Session: {
            /** Format: int32 */
            blocks?: number;
            /** Format: int64 */
            consumedSeconds?: number;
            /** Format: date-time */
            endedAt?: string;
            /** Format: int32 */
            fnbDue?: number;
            /** Format: int32 */
            gamingDue?: number;
            /** Format: int64 */
            id?: number;
            /** Format: int64 */
            memberId?: number;
            /** Format: int32 */
            netOutstanding?: number;
            /** Format: int32 */
            paidBlocks?: number;
            /** Format: int64 */
            purchasedSeconds?: number;
            /** Format: int64 */
            queueEntryId?: number;
            /** Format: int64 */
            remainingSeconds?: number;
            /** Format: date-time */
            serverTime?: string;
            /** Format: int64 */
            shiftId?: number;
            /** Format: date-time */
            startedAt?: string;
            state?: string;
            /** Format: int64 */
            stationId?: number;
            /** Format: int32 */
            unpaidBlocks?: number;
        };
        SessionResponse: {
            accessToken?: string;
            /** Format: int64 */
            expiresIn?: number;
            /** Format: int64 */
            shiftId?: number;
            staff?: components["schemas"]["Staff"];
            terminal?: string;
            tokenType?: string;
        };
        SettledQueueToken: {
            /** Format: int64 */
            queueEntryId?: number;
            /** Format: date */
            tokenDate?: string;
            /** Format: int32 */
            tokenNo?: number;
        };
        /** @description Settle a session's bill or a counter cart */
        SettleRequest: {
            playTickets?: components["schemas"]["PlayTicketRequest"][];
            /** Format: int32 */
            redeemPoints?: number;
            splits?: components["schemas"]["PaymentSplit"][];
            target: components["schemas"]["SettleTarget"];
            tournamentEntries?: components["schemas"]["TournamentEntryRequest"][];
        };
        /** @description The transaction and the receipt a settle produced */
        SettleResult: {
            entryTokens?: string[];
            /** Format: int64 */
            printJobId?: number;
            publicId?: string;
            queueTokens?: components["schemas"]["SettledQueueToken"][];
            /** Format: int64 */
            transactionId?: number;
        };
        SettleTarget: {
            /** Format: int64 */
            cartId?: number;
            /** Format: int64 */
            sessionId?: number;
        };
        /** @description A till session: who opened it, on which terminal, and how the drawer counted at the end */
        Shift: {
            /** Format: date-time */
            closedAt?: string;
            /** Format: int32 */
            countedCash?: number;
            /** Format: int32 */
            discrepancy?: number;
            /** Format: int32 */
            expectedCash?: number;
            handoverNote?: string;
            /** Format: int64 */
            id?: number;
            open?: boolean;
            /** Format: date-time */
            openedAt?: string;
            /** Format: int32 */
            openingFloat?: number;
            /** Format: int64 */
            staffId?: number;
            terminal?: string;
        };
        ShiftCash: {
            /** Format: int32 */
            counted?: number;
            /** Format: int32 */
            discrepancy?: number;
            /** Format: int32 */
            expected?: number;
            /** Format: int32 */
            expenses?: number;
            /** Format: int32 */
            openingFloat?: number;
            /** Format: int32 */
            takings?: number;
        };
        ShiftExpenseCategoryTotal: {
            /** Format: int32 */
            amount?: number;
            category?: string;
        };
        ShiftExpenseLine: {
            /** Format: int32 */
            amount?: number;
            /** Format: date-time */
            at?: string;
            category?: string;
            description?: string;
            /** Format: int64 */
            id?: number;
        };
        ShiftExpenses: {
            byCategory?: components["schemas"]["ShiftExpenseCategoryTotal"][];
            /** Format: int32 */
            count?: number;
            lines?: components["schemas"]["ShiftExpenseLine"][];
            /** Format: int32 */
            total?: number;
        };
        /** @description A shift's takings by method and category, its petty cash, and what that says should be in the drawer */
        ShiftReport: {
            cash?: components["schemas"]["ShiftCash"];
            /** Format: date-time */
            closedAt?: string;
            expenses?: components["schemas"]["ShiftExpenses"];
            handoverNote?: string;
            kind?: string;
            /** Format: date-time */
            openedAt?: string;
            /** Format: int32 */
            openingFloat?: number;
            /** Format: int64 */
            printJobId?: number;
            /** Format: date-time */
            serverTime?: string;
            /** Format: int64 */
            shiftId?: number;
            /** Format: int64 */
            staffId?: number;
            takings?: components["schemas"]["ShiftTakings"];
            terminal?: string;
        };
        ShiftTakings: {
            byMethod?: components["schemas"]["ShiftTakingsRow"][];
            /** Format: int32 */
            pointsEarned?: number;
            /** Format: int32 */
            pointsRedeemed?: number;
            /** Format: int32 */
            refundCount?: number;
            /** Format: int32 */
            saleCount?: number;
            totals?: components["schemas"]["ShiftTakingsRow"];
        };
        ShiftTakingsRow: {
            /** Format: int32 */
            booking?: number;
            /** Format: int32 */
            fnb?: number;
            /** Format: int32 */
            gaming?: number;
            method?: string;
            /** Format: int32 */
            total?: number;
            /** Format: int32 */
            tournament?: number;
        };
        SseEmitter: {
            /** Format: int64 */
            timeout?: number | null;
        };
        Staff: {
            active?: boolean;
            avatarColor?: string;
            /** Format: date-time */
            createdAt?: string;
            /** Format: int64 */
            id?: number;
            name?: string;
            /** @enum {string} */
            role?: "ADMIN" | "MANAGER" | "CASHIER";
        };
        Station: {
            arrival?: components["schemas"]["StationArrival"];
            /** @enum {string} */
            consoleType?: "PS5" | "PS4";
            /** Format: date-time */
            createdAt?: string;
            /** @enum {string} */
            floorState?: "FREE" | "OPEN" | "RUNNING" | "PAUSED" | "LOCKED" | "RESERVED" | "BOOKED" | "MAINTENANCE";
            /** Format: int64 */
            id?: number;
            match?: components["schemas"]["StationMatch"];
            name?: string;
            session?: components["schemas"]["StationSession"];
            /** @enum {string} */
            status?: "AVAILABLE" | "MAINTENANCE";
        };
        /** @description A checked-in booking waiting for this console */
        StationArrival: {
            /**
             * Format: int32
             * @description Prepaid 30-minute blocks that load when the token is seated
             */
            blocks?: number;
            /** Format: int64 */
            bookingId?: number;
            name?: string;
            /** Format: int64 */
            queueEntryId?: number;
            /** Format: int32 */
            token?: number;
        };
        /** @description The consoles this event holds */
        StationBlocksRequest: {
            stationIds: number[];
        };
        StationMatch: {
            /** Format: int64 */
            matchId?: number;
            playerA?: string;
            playerB?: string;
            /** Format: int64 */
            remainingSeconds?: number;
            /**
             * Format: int32
             * @description 1 = first round
             */
            round?: number;
            /** Format: int32 */
            slot?: number;
            /** @description Past zero — the card reads "match over" until a winner is recorded */
            timeUp?: boolean;
            /** Format: int64 */
            tournamentId?: number;
            tournamentName?: string;
        };
        StationSession: {
            /**
             * Format: int32
             * @description Non-removed 30-minute blocks bought so far
             */
            blocks?: number;
            /** Format: int64 */
            id?: number;
            /** Format: int64 */
            memberId?: number;
            /**
             * Format: int32
             * @description Blocks already paid — prepaid or settled
             */
            paidBlocks?: number;
            /** Format: int64 */
            remainingSeconds?: number;
            /** Format: date-time */
            startedAt?: string;
            /** @enum {string} */
            state?: "OPEN" | "RUNNING" | "PAUSED" | "LOCKED";
        };
        /** @description Where the venue's outbox stands against the cloud */
        SyncStatus: {
            /** Format: date-time */
            lastSyncedAt?: string;
            /** Format: int64 */
            pendingOps?: number;
            state?: string;
        };
        /** @description Per-terminal appearance and behaviour (design.md §6) */
        TerminalSettings: {
            /** @example #ec3013 */
            accent?: string;
            /**
             * Format: int32
             * @description 0 = off, else 2, 5 or 10
             */
            autoLockMin?: number;
            /** @enum {string} */
            fontScale?: "COMPACT" | "DEFAULT" | "LARGE";
            loginBgImageId?: string | null;
            /**
             * Format: int32
             * @description 1 or 2
             */
            receiptCopies?: number;
            sound?: boolean;
            /** @enum {string} */
            theme?: "DARK" | "LIGHT";
        };
        TopupRequest: {
            /**
             * Format: int32
             * @description Integer BDT added to the wallet
             */
            amount: number;
            /**
             * @description How the money came in — a wallet cannot fund itself, so no WALLET
             * @enum {string}
             */
            method: "CASH" | "BKASH" | "NAGAD";
            /** @description bKash/Nagad TrxID, entered by hand in the MVP */
            paymentRef?: string;
        };
        Tournament: {
            /** @enum {string} */
            cadence?: "WEEKLY" | "MONTHLY" | "ONE_OFF";
            cancelledReason?: string;
            /** Format: date-time */
            createdAt?: string;
            /** Format: int32 */
            entries?: number;
            /** Format: int32 */
            entryFee?: number;
            game?: string;
            /** Format: int64 */
            id?: number;
            /** Format: int32 */
            matchDurationMin?: number;
            /** Format: int32 */
            maxPlayers?: number;
            name?: string;
            /** Format: int32 */
            prizePool?: number;
            /** Format: date-time */
            scheduledAt?: string;
            /** Format: int32 */
            slotsLeft?: number;
            /** @enum {string} */
            status?: "OPEN" | "LIVE" | "DONE" | "CANCELLED";
            /** Format: int64 */
            winnerEntryId?: number;
            /** @description The champion; null until the final is decided */
            winnerName?: string;
        };
        TournamentCancellation: {
            /** Format: int32 */
            entriesRefunded?: number;
            refunds?: components["schemas"]["TournamentRefund"][];
            tournament?: components["schemas"]["Tournament"];
        };
        TournamentDetail: {
            /** @description First round first; empty before the draw */
            bracket?: components["schemas"]["TournamentMatch"][];
            entries?: components["schemas"]["TournamentEntry"][];
            /** @description Consoles blocked for this event */
            stationIds?: number[];
            tournament?: components["schemas"]["Tournament"];
        };
        TournamentEntry: {
            checkedIn?: boolean;
            /** Format: date-time */
            createdAt?: string;
            /** Format: int64 */
            id?: number;
            /** Format: int64 */
            memberId?: number;
            playerName?: string;
            refunded?: boolean;
            /**
             * Format: int32
             * @description Sale order; printed as TOKEN #NN
             */
            seed?: number;
            /** Format: int64 */
            tournamentId?: number;
            /** Format: int64 */
            txId?: number;
        };
        TournamentEntryRequest: {
            playerName?: string;
            /** Format: int64 */
            tournamentId: number;
        };
        TournamentFinance: {
            /** Format: int32 */
            allocatedStations?: number;
            /**
             * Format: int32
             * @description Mean rate-card hourly price of the allocated consoles' types
             */
            avgHourlyRate?: number;
            /**
             * Format: int32
             * @description Tickets still paid for; a refunded entry is not revenue
             */
            entries?: number;
            /** Format: int32 */
            entryFee?: number;
            /**
             * Format: int32
             * @description netProfit - opportunityCost
             */
            extraMargin?: number;
            /** Format: int32 */
            matchDurationMin?: number;
            /**
             * Format: int32
             * @description N-1 for the configured cap — the consoles were held for the whole event, whatever the turnout
             */
            matches?: number;
            /**
             * Format: int32
             * @description revenue - prizePool
             */
            netProfit?: number;
            /**
             * Format: int32
             * @description matches x matchDurationMin/60 x avgHourlyRate
             */
            opportunityCost?: number;
            /** Format: int32 */
            prizePool?: number;
            /**
             * Format: int32
             * @description entries x entryFee
             */
            revenue?: number;
            verdict?: string;
        };
        TournamentMatch: {
            /** @description One player, one empty bracket position — decided by the draw, never played */
            bye?: boolean;
            /** Format: date-time */
            decidedAt?: string;
            /** Format: int64 */
            decidedBy?: number;
            /** Format: int64 */
            entryA?: number;
            /** Format: int64 */
            entryB?: number;
            /** Format: int32 */
            extraMinutes?: number;
            /** Format: int64 */
            id?: number;
            /**
             * Format: int64
             * @description The match this winner advances into; null on the final
             */
            nextMatchId?: number;
            playerA?: string;
            playerB?: string;
            /**
             * Format: int64
             * @description (matchDurationMin + extraMinutes) x 60 - elapsed, floored at 0; null unless the match is on
             */
            remainingSeconds?: number;
            /**
             * Format: int32
             * @description 1 = first round
             */
            round?: number;
            /**
             * Format: int32
             * @description Position in the round, from 1
             */
            slot?: number;
            /** Format: date-time */
            startedAt?: string;
            /** Format: int64 */
            stationId?: number;
            stationName?: string;
            /** @description The countdown has hit zero — the match is over and the winner still has to be recorded */
            timeUp?: boolean;
            /** Format: int64 */
            winnerEntryId?: number;
            winnerName?: string;
        };
        TournamentRefund: {
            /** Format: int32 */
            amount?: number;
            publicId?: string;
            /** Format: int64 */
            transactionId?: number;
        };
        UpdateBookingSettingsRequest: {
            /** Format: int32 */
            cancelCutoffHours?: number;
            enabled?: boolean;
            /** Format: int32 */
            packageFee?: number;
        };
        UpdateItemRequest: {
            active?: boolean;
            /** @enum {string} */
            category?: "BEVERAGE" | "FOOD" | "SNACK" | "EXTRAS";
            name?: string;
            /** Format: int32 */
            price?: number;
            /** Format: int32 */
            reorderAt?: number;
            /** Format: int32 */
            stock?: number;
        };
        UpdatePricingRequest: {
            /** @enum {string} */
            consoleType?: "PS5" | "PS4";
            /** Format: int32 */
            morningDiscountPct?: number;
            /** @example 14:00 */
            morningEnd?: string;
            /** @example 10:00 */
            morningStart?: string;
            /** Format: int32 */
            perHalfHour?: number;
            /** Format: int32 */
            perHour?: number;
        };
        UpdateStaffRequest: {
            active?: boolean;
            name?: string;
            pin?: string;
            /** @enum {string} */
            role?: "MANAGER" | "CASHIER";
        };
        UpdateStationRequest: {
            /** @enum {string} */
            consoleType?: "PS5" | "PS4";
            name?: string;
            /** @enum {string} */
            status?: "AVAILABLE" | "MAINTENANCE";
        };
        UpdateTerminalSettingsRequest: {
            /** @example #ec3013 */
            accent: string;
            /**
             * Format: int32
             * @description 0 = off, else 2, 5 or 10
             */
            autoLockMin: number;
            /** @enum {string} */
            fontScale: "COMPACT" | "DEFAULT" | "LARGE";
            /** @description the id this terminal's last upload returned, or null to remove */
            loginBgImageId?: string | null;
            /**
             * Format: int32
             * @description 1 or 2
             */
            receiptCopies: number;
            sound: boolean;
            /** @enum {string} */
            theme: "DARK" | "LIGHT";
        };
        UpdateTournamentRequest: {
            /** @enum {string} */
            cadence?: "WEEKLY" | "MONTHLY" | "ONE_OFF";
            /** Format: int32 */
            entryFee?: number;
            game?: string;
            /** Format: int32 */
            matchDurationMin?: number;
            /** Format: int32 */
            maxPlayers?: number;
            name?: string;
            /** Format: int32 */
            prizePool?: number;
            /** Format: date-time */
            scheduledAt?: string;
        };
        VoidRequest: {
            reason: string;
        };
        /** @description The reversal transaction a void produced */
        VoidResult: {
            publicId?: string;
            /** Format: int32 */
            refunded?: number;
            /** Format: int64 */
            transactionId?: number;
            voidedPublicId?: string;
            /** Format: int64 */
            voidedTransactionId?: number;
        };
    };
    responses: never;
    parameters: never;
    requestBodies: never;
    headers: never;
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    feed: {
        parameters: {
            query?: {
                unread?: boolean;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Alert"][];
                };
            };
        };
    };
    read_1: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Alert"];
                };
            };
        };
    };
    readAll: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Alert"][];
                };
            };
        };
    };
    login: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["LoginRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["SessionResponse"];
                };
            };
        };
    };
    logout: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    refresh: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["SessionResponse"];
                };
            };
        };
    };
    get_2: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["BookingSettings"];
                };
            };
        };
    };
    update_4: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["UpdateBookingSettingsRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["BookingSettings"];
                };
            };
        };
    };
    list_7: {
        parameters: {
            query?: {
                tab?: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Booking"][];
                };
            };
        };
    };
    create_5: {
        parameters: {
            query?: never;
            header: {
                /** @description UUID. The first call is stored for 48 h; an identical retry replays it with Idempotency-Replayed: true and the same bookingId / transactionId / printJobId. */
                "Idempotency-Key": string;
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateBookingRequest"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["BookingCreated"];
                };
            };
        };
    };
    get_9: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Booking"];
                };
            };
        };
    };
    cancel_1: {
        parameters: {
            query?: never;
            header: {
                /** @description UUID. An identical retry replays the stored response, so the refund is written once. */
                "Idempotency-Key": string;
            };
            path: {
                id: number;
            };
            cookie?: never;
        };
        requestBody?: {
            content: {
                "application/json": components["schemas"]["CancelBookingRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["BookingCancelled"];
                };
            };
        };
    };
    checkIn_1: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["BookingCheckedIn"];
                };
            };
        };
    };
    open_2: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateCartRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Cart"];
                };
            };
        };
    };
    putLine: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: number;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CartLineRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Cart"];
                };
            };
        };
    };
    subscribe: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "text/event-stream": components["schemas"]["SseEmitter"];
                };
            };
        };
    };
    list_6: {
        parameters: {
            query?: {
                shiftId?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Expense"][];
                };
            };
        };
    };
    record: {
        parameters: {
            query?: {
                voucher?: boolean;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateExpenseRequest"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Expense"];
                };
            };
        };
    };
    list_5: {
        parameters: {
            query?: {
                active?: boolean;
                category?: "BEVERAGE" | "FOOD" | "SNACK" | "EXTRAS";
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Item"][];
                };
            };
        };
    };
    create_4: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateItemRequest"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Item"];
                };
            };
        };
    };
    get_5: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Item"];
                };
            };
        };
    };
    delete_2: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description No Content */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    update_8: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: number;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["UpdateItemRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Item"];
                };
            };
        };
    };
    read: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["PrefsResponse"];
                };
            };
        };
    };
    update_3: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["PrefsRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["PrefsResponse"];
                };
            };
        };
    };
    search: {
        parameters: {
            query?: {
                page?: number;
                q?: string;
                size?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["PageResponseMember"];
                };
            };
        };
    };
    create_3: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateMemberRequest"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Member"];
                };
            };
        };
    };
    get_8: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["MemberDetail"];
                };
            };
        };
    };
    redeemPoints: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: number;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["RedeemPointsRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Member"];
                };
            };
        };
    };
    topup: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: number;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["TopupRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Member"];
                };
            };
        };
    };
    overview: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Overview"];
                };
            };
        };
    };
    settle: {
        parameters: {
            query?: never;
            header: {
                /** @description UUID. The first call is stored for 48 h; an identical retry replays it with Idempotency-Replayed: true and the same transactionId / printJobId. */
                "Idempotency-Key": string;
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SettleRequest"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["SettleResult"];
                };
            };
        };
    };
    voidPayment: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: number;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["VoidRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["VoidResult"];
                };
            };
        };
    };
    rail: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["QueueEntry"][];
                };
            };
        };
    };
    remove: {
        parameters: {
            query?: {
                reason?: string;
            };
            header?: never;
            path: {
                id: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["QueueEntryRemoved"];
                };
            };
        };
    };
    seat: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: number;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SeatQueueEntryRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["QueueEntrySeated"];
                };
            };
        };
    };
    sell: {
        parameters: {
            query?: never;
            header: {
                /** @description UUID. The first call is stored for 48 h; an identical retry replays it with Idempotency-Replayed: true and the same token / transactionId / printJobId — no second number comes off the daily counter. */
                "Idempotency-Key": string;
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SellPlayTicketRequest"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["PlayTicketSold"];
                };
            };
        };
    };
    list: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Pricing"][];
                };
            };
        };
    };
    update_1: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["UpdatePricingRequest"][];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Pricing"][];
                };
            };
        };
    };
    get_1: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                consoleType: "PS5" | "PS4";
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Pricing"];
                };
            };
        };
    };
    update_2: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                consoleType: "PS5" | "PS4";
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["UpdatePricingRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Pricing"];
                };
            };
        };
    };
    get_7: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["PrintJob"];
                };
            };
        };
    };
    render: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["PrintRender"];
                };
            };
        };
    };
    reprint: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: number;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ReprintRequest"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["PrintJob"];
                };
            };
        };
    };
    retry: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["PrintJob"];
                };
            };
        };
    };
    list_8: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Printer"][];
                };
            };
        };
    };
    test: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                printerId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["PrintJob"];
                };
            };
        };
    };
    setDefault: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["DefaultPrinterRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Printer"];
                };
            };
        };
    };
    report: {
        parameters: {
            query?: {
                from?: string;
                to?: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Report"];
                };
            };
        };
    };
    list_4: {
        parameters: {
            query?: {
                active?: boolean;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Session"][];
                };
            };
        };
    };
    open_1: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateSessionRequest"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Session"];
                };
            };
        };
    };
    get_6: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Session"];
                };
            };
        };
    };
    bill: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Bill"];
                };
            };
        };
    };
    blocks: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: number;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["BlocksRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Session"];
                };
            };
        };
    };
    clock: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: number;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ClockRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Session"];
                };
            };
        };
    };
    end: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Session"];
                };
            };
        };
    };
    history: {
        parameters: {
            query?: {
                page?: number;
                size?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["PageResponseShift"];
                };
            };
        };
    };
    open: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["OpenShiftRequest"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Shift"];
                };
            };
        };
    };
    close: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CloseShiftRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ShiftReport"];
                };
            };
        };
    };
    xReport: {
        parameters: {
            query?: {
                print?: boolean;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ShiftReport"];
                };
            };
        };
    };
    list_3: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Staff"][];
                };
            };
        };
    };
    create_2: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateStaffRequest"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Staff"];
                };
            };
        };
    };
    delete_1: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description No Content */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    update_7: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: number;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["UpdateStaffRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Staff"];
                };
            };
        };
    };
    list_2: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Station"][];
                };
            };
        };
    };
    create_1: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateStationRequest"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Station"];
                };
            };
        };
    };
    get_4: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Station"];
                };
            };
        };
    };
    delete: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description No Content */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    update_6: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: number;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["UpdateStationRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Station"];
                };
            };
        };
    };
    status: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["SyncStatus"];
                };
            };
        };
    };
    get: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["TerminalSettings"];
                };
            };
        };
    };
    update: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["UpdateTerminalSettingsRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["TerminalSettings"];
                };
            };
        };
    };
    uploadLoginBg: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: {
            content: {
                "multipart/form-data": {
                    /** Format: binary */
                    file: string;
                };
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["LoginBgUploaded"];
                };
            };
        };
    };
    loginBg: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                imageId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": string;
                };
            };
        };
    };
    checkIn: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: number;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CheckInRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["TournamentEntry"];
                };
            };
        };
    };
    list_1: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Tournament"][];
                };
            };
        };
    };
    create: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateTournamentRequest"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["TournamentDetail"];
                };
            };
        };
    };
    get_3: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["TournamentDetail"];
                };
            };
        };
    };
    update_5: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: number;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["UpdateTournamentRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["TournamentDetail"];
                };
            };
        };
    };
    setBlocks: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: number;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["StationBlocksRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["TournamentDetail"];
                };
            };
        };
    };
    generateBracket: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["TournamentDetail"];
                };
            };
        };
    };
    cancel: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: number;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CancelTournamentRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["TournamentCancellation"];
                };
            };
        };
    };
    sellEntry: {
        parameters: {
            query?: never;
            header: {
                /** @description UUID. An identical retry replays the stored response with Idempotency-Replayed: true — the same entry, the same QR, one charge. */
                "Idempotency-Key": string;
            };
            path: {
                id: number;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SellEntryRequest"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["EntrySold"];
                };
            };
        };
    };
    finance: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["TournamentFinance"];
                };
            };
        };
    };
    board: {
        parameters: {
            query?: {
                pending?: boolean;
            };
            header?: never;
            path: {
                id: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["MatchBoard"];
                };
            };
        };
    };
    extend: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: number;
                mid: number;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ExtendMatchRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["TournamentMatch"];
                };
            };
        };
    };
    start: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: number;
                mid: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["TournamentMatch"];
                };
            };
        };
    };
    recordWinner: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: number;
                mid: number;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["RecordWinnerRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["MatchDecision"];
                };
            };
        };
    };
    history_1: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Tournament"][];
                };
            };
        };
    };
}
