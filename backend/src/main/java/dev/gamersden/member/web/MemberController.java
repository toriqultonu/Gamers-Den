package dev.gamersden.member.web;

import dev.gamersden.common.security.Roles;
import dev.gamersden.common.web.PageResponse;
import dev.gamersden.member.domain.MemberService;
import dev.gamersden.member.domain.WalletService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /members} — S6 and the member attach behind POS, bookings and the play queue. Every
 * operator registers customers and moves their wallet: api-contract.md §1's matrix grants
 * "Members create/top-up/redeem" to Admin, Manager and Cashier alike.
 *
 * <p>Both wallet routes are on the idempotency list (§1, matched as
 * {@code POST /members/*​/wallet/*}) — the filter rejects them without an {@code Idempotency-Key}
 * and replays the stored response on a retry, so a double-tapped top-up credits the wallet once.
 */
@RestController
@RequestMapping("/members")
@Validated
@Tag(name = "Members, wallet & points")
public class MemberController {

    private final MemberService members;
    private final WalletService wallet;

    public MemberController(MemberService members, WalletService wallet) {
        this.members = members;
        this.wallet = wallet;
    }

    @GetMapping
    @PreAuthorize(Roles.ANY_STAFF)
    @Operation(summary = "Search the directory",
            description = "One box over name and phone: the name matches anywhere, the phone on "
                    + "digits so typed separators do not matter. No q lists everyone, by name.")
    public PageResponse<MemberView> search(@RequestParam(required = false) String q,
                                           @RequestParam(defaultValue = "0") @Min(0) int page,
                                           @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Order.asc("name").ignoreCase()));
        return PageResponse.of(members.search(q, pageable), MemberView::of);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.ANY_STAFF)
    @Operation(summary = "Register a member",
            description = "409 DUPLICATE_PHONE when the number is already on file — the phone is "
                    + "compared normalised. An opening top-up is a separate wallet call.")
    public MemberView create(@Valid @RequestBody CreateMemberRequest request) {
        return MemberView.of(members.create(request.name(), request.phone(),
                request.preferredConsole(), request.games()));
    }

    @GetMapping("/{id}")
    @PreAuthorize(Roles.ANY_STAFF)
    @Operation(summary = "One member with their recent visits",
            description = "Visits are the last sessions the member was attached to, newest first. "
                    + "The bookings list joins here in B15.")
    public MemberDetailView get(@PathVariable Long id) {
        return MemberDetailView.of(members.profile(id));
    }

    @PostMapping("/{id}/wallet/topup")
    @PreAuthorize(Roles.ANY_STAFF)
    @Operation(summary = "Add money to a wallet",
            description = "Requires an Idempotency-Key. Writes the TOPUP ledger row and the "
                    + "members.wallet total in one transaction.")
    public MemberView topup(@PathVariable Long id, @Valid @RequestBody TopupRequest request) {
        return MemberView.of(wallet.topUp(id, request.amount(), request.method(),
                request.paymentRef()));
    }

    @PostMapping("/{id}/wallet/redeem-points")
    @PreAuthorize(Roles.ANY_STAFF)
    @Operation(summary = "Convert points to wallet balance",
            description = "Requires an Idempotency-Key. 1 point = ৳1; both ledgers and both "
                    + "columns move in one transaction. 409 INSUFFICIENT_POINTS.")
    public MemberView redeemPoints(@PathVariable Long id, @Valid @RequestBody RedeemPointsRequest request) {
        return MemberView.of(wallet.redeemPointsToWallet(id, request.points()));
    }
}
