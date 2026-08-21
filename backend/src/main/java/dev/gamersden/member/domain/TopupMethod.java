package dev.gamersden.member.domain;

/**
 * How a wallet top-up was tendered — the {@code payment_splits.method} set minus {@code WALLET},
 * because a wallet cannot fund itself.
 *
 * <p>The value is recorded in the log line, not in a column: {@code wallet_ledger} carries only
 * {@code delta}, {@code kind} and {@code ref_tx_id} (DDL §2), and the transaction that would own
 * the tender detail is B10's. See {@link WalletService}.
 */
public enum TopupMethod {
    CASH,
    BKASH,
    NAGAD
}
