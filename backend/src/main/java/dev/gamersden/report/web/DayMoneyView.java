package dev.gamersden.report.web;

import dev.gamersden.report.domain.DayMoney;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

/**
 * One bar of a revenue trend — S9's 14-day stacked chart and S2's 30-day one.
 *
 * <p>Every day in the range is here, quiet days as zeroes, so the array's length is the range's
 * length and its order is the axis. {@code gaming} and {@code fnb} are the two stacked segments
 * design.md S9 draws; {@code tournament} and {@code booking} are the same money the X/Z reports on
 * its own lines.
 *
 * @param date the venue day, {@code Asia/Dhaka} — a 01:30 sale belongs to the night before
 */
@Schema(name = "ReportTrendPoint", description = "One venue day of takings, expenses and profit")
public record DayMoneyView(LocalDate date,
                           int revenue,
                           int gaming,
                           int fnb,
                           int tournament,
                           int booking,
                           int pointsRedeemed,
                           int expenses,
                           int netProfit,
                           int transactions,
                           int sales) {

    public static DayMoneyView of(DayMoney day) {
        return new DayMoneyView(day.day(), day.money().revenue(), day.money().gaming(),
                day.money().fnb(), day.money().tournament(), day.money().booking(),
                day.money().pointsRedeemed(), day.money().expenses(), day.money().netProfit(),
                day.money().transactions(), day.money().sales());
    }
}
