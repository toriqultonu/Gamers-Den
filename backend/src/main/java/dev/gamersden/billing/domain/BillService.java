package dev.gamersden.billing.domain;

import dev.gamersden.common.error.NotFoundException;
import dev.gamersden.common.spi.CartLookup;
import dev.gamersden.common.spi.MemberPointsLookup;
import dev.gamersden.common.spi.SessionBillLookup;
import dev.gamersden.common.spi.TournamentBillLookup;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code GET /sessions/{id}/bill} (api-contract.md, "Billing &amp; payments") — gathers the four
 * reads a bill is made of and hands them to {@link Bill}, which does the arithmetic.
 *
 * <p>The split is deliberate. Every rule the FE's bill panel depends on — unbilled blocks only,
 * prepaid excluded, the points cap — lives in {@link Bill} as pure functions of its inputs and is
 * unit-tested without a database; this class only knows which door to knock on. Cross-package
 * reads all go through {@code common.spi}, never a foreign repository (ARCHITECTURE.md §3).
 *
 * <p>Read-only by construction: quoting a bill must never move a block, a cart, a point or a
 * wallet. The money write is {@code POST /payments} in B10.
 */
@Service
public class BillService {

    private final SessionBillLookup sessions;
    private final CartLookup carts;
    private final TournamentBillLookup tournaments;
    private final MemberPointsLookup members;

    public BillService(SessionBillLookup sessions,
                       CartLookup carts,
                       TournamentBillLookup tournaments,
                       MemberPointsLookup members) {
        this.sessions = sessions;
        this.carts = carts;
        this.tournaments = tournaments;
        this.members = members;
    }

    @Transactional(readOnly = true)
    public Bill of(long sessionId) {
        SessionBillLookup.BillableSession session = sessions.findForBill(sessionId)
                .orElseThrow(() -> new NotFoundException("Session", sessionId));
        return Bill.of(session,
                carts.unsettledCart(sessionId).orElse(null),
                tournaments.unbilledEntriesFor(sessionId),
                loyaltyOf(session.memberId()));
    }

    /** A walk-in seat has no member; so does one whose member row has since gone. */
    private MemberPointsLookup.Loyalty loyaltyOf(Long memberId) {
        return memberId == null ? null : members.loyaltyOf(memberId).orElse(null);
    }
}
