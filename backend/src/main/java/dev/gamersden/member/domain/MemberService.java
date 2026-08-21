package dev.gamersden.member.domain;

import dev.gamersden.common.error.ConflictException;
import dev.gamersden.common.error.ErrorCode;
import dev.gamersden.common.error.NotFoundException;
import dev.gamersden.common.error.ValidationFailedException;
import dev.gamersden.common.spi.MemberVisitLookup;
import dev.gamersden.common.spi.StationLookup;
import dev.gamersden.member.repo.MemberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * The member directory (api-contract.md, "Members, wallet, points"): search, register, detail.
 * Money lives next door in {@link WalletService}; this service never touches a balance.
 *
 * <p>The phone number is the identity: it is stored normalised ({@link Phones}) so 409
 * {@code DUPLICATE_PHONE} catches the same customer typed two ways, and so the booking form's
 * member attach finds them either way.
 */
@Service
public class MemberService {

    /** How much of the visits strip S6 shows on the member detail. */
    public static final int RECENT_VISITS = 10;

    private static final Logger log = LoggerFactory.getLogger(MemberService.class);

    private final MemberRepository members;
    private final MemberVisitLookup visits;
    private final StationLookup stations;

    public MemberService(MemberRepository members, MemberVisitLookup visits, StationLookup stations) {
        this.members = members;
        this.visits = visits;
        this.stations = stations;
    }

    // ---- reads ------------------------------------------------------------------------------

    /** {@code GET /members?q=} — the whole directory when {@code q} is blank. */
    @Transactional(readOnly = true)
    public Page<Member> search(String q, Pageable pageable) {
        if (q == null || q.isBlank()) {
            return members.findAll(pageable);
        }
        String text = q.trim();
        return members.search(text, Phones.digitsIn(text), pageable);
    }

    @Transactional(readOnly = true)
    public Member get(long id) {
        return members.findById(id).orElseThrow(() -> new NotFoundException("Member", id));
    }

    /**
     * {@code GET /members/{id}} — the member plus their last {@value #RECENT_VISITS} seats, each
     * named by its station. Both halves come from other packages' lookups, never their tables.
     */
    @Transactional(readOnly = true)
    public MemberProfile profile(long id) {
        Member member = get(id);
        List<MemberVisit> recent = visits.recentVisits(id, RECENT_VISITS).stream()
                .map(this::describe)
                .toList();
        return new MemberProfile(member, recent);
    }

    // ---- writes -----------------------------------------------------------------------------

    /**
     * Register a walk-in. An opening top-up (S6a) is a separate, idempotent call to
     * {@code /wallet/topup} — money never rides along on a plain create.
     */
    @Transactional
    public Member create(String name, String phone, String preferredConsole, List<String> games) {
        String trimmedName = requireText(name, "name");
        String normalisedPhone = Phones.normalise(phone);
        if (normalisedPhone.isBlank()) {
            throw ValidationFailedException.onField("phone", "A phone number is required");
        }
        if (members.existsByPhone(normalisedPhone)) {
            throw new ConflictException(ErrorCode.DUPLICATE_PHONE,
                    "A member with phone %s already exists".formatted(normalisedPhone),
                    Map.of("phone", normalisedPhone));
        }
        Member member = new Member(trimmedName, normalisedPhone);
        member.setPreferredConsole(preferredConsole);
        member.setGames(gamesArray(games));
        Member created = members.save(member);
        log.info("member {} registered as {}", created.getId(), trimmedName);
        return created;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ValidationFailedException.onField(field, "A " + field + " is required");
        }
        return value.trim();
    }

    private static String[] gamesArray(List<String> games) {
        if (games == null) {
            return null;
        }
        String[] cleaned = games.stream()
                .filter(game -> game != null && !game.isBlank())
                .map(String::trim)
                .toArray(String[]::new);
        return cleaned.length == 0 ? null : cleaned;
    }

    private MemberVisit describe(MemberVisitLookup.Visit visit) {
        StationLookup.StationInfo station = stations.find(visit.stationId()).orElse(null);
        return new MemberVisit(visit.sessionId(), visit.stationId(),
                station == null ? null : station.name(),
                station == null ? null : station.consoleType(),
                visit.state(), visit.blocks(), visit.playedSeconds(),
                visit.startedAt(), visit.endedAt());
    }
}
