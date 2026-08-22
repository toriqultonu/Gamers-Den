package dev.gamersden.shift.web;

import dev.gamersden.shift.domain.Shift;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

/**
 * A shift row — what {@code POST /shifts} answers with and what {@code GET /shifts} lists.
 *
 * <p>The four drawer figures are the Z snapshot and exist only on a closed shift (invariant §5.4);
 * while it is open they are omitted, and the live version is
 * {@code GET /shifts/current/x-report}.
 */
@Schema(name = "Shift", description = "A till session: who opened it, on which terminal, and how "
        + "the drawer counted at the end")
public record ShiftView(long id,
                        String terminal,
                        long staffId,
                        OffsetDateTime openedAt,
                        OffsetDateTime closedAt,
                        int openingFloat,
                        Integer countedCash,
                        Integer expectedCash,
                        Integer discrepancy,
                        String handoverNote,
                        boolean open) {

    public static ShiftView of(Shift shift) {
        return new ShiftView(shift.getId(), shift.getTerminal(), shift.getStaffId(),
                shift.getOpenedAt(), shift.getClosedAt(), shift.getOpeningFloat(),
                shift.getCountedCash(), shift.getExpectedCash(), shift.getDiscrepancy(),
                shift.getHandoverNote(), shift.getClosedAt() == null);
    }
}
