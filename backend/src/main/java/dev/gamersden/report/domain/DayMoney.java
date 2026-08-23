package dev.gamersden.report.domain;

import java.time.LocalDate;

/**
 * One venue day of the money line. Every day in a window gets one of these, quiet days included —
 * a gap in a bar chart has to be a bar of height zero, not a missing bar, or the axis lies.
 */
public record DayMoney(LocalDate day, MoneyTotals money) {

    public static DayMoney empty(LocalDate day) {
        return new DayMoney(day, MoneyTotals.ZERO);
    }
}
