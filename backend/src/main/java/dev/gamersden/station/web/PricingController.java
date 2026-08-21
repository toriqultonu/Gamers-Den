package dev.gamersden.station.web;

import dev.gamersden.common.error.ValidationFailedException;
import dev.gamersden.common.security.Roles;
import dev.gamersden.station.domain.ConsoleType;
import dev.gamersden.station.domain.Pricing;
import dev.gamersden.station.domain.PricingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code /pricing} — every operator reads the rate card (the POS and the booking form price
 * against it), only an Admin writes it (api-contract.md, permission matrix).
 *
 * <p>A write moves <strong>new blocks only</strong>. Blocks already on a session keep the price
 * they were sold at, because {@code session_blocks.price} is a snapshot — nothing in this
 * controller can reach it.
 */
@RestController
@RequestMapping("/pricing")
@Tag(name = "Pricing")
public class PricingController {

    private final PricingService pricing;

    public PricingController(PricingService pricing) {
        this.pricing = pricing;
    }

    @GetMapping
    @PreAuthorize(Roles.ANY_STAFF)
    @Operation(summary = "The rate card, one entry per console type")
    public List<PricingView> list() {
        return pricing.list().stream().map(this::view).toList();
    }

    @GetMapping("/{consoleType}")
    @PreAuthorize(Roles.ANY_STAFF)
    @Operation(summary = "One console's rates")
    public PricingView get(@PathVariable ConsoleType consoleType) {
        return view(pricing.get(consoleType));
    }

    @PutMapping
    @PreAuthorize(Roles.ADMIN)
    @Operation(summary = "Set rates for one or more console types (Admin)",
            description = "Each entry names its consoleType. New blocks only — running sessions "
                    + "keep the prices they purchased.")
    public List<PricingView> update(@RequestBody List<UpdatePricingRequest> rates) {
        requireOneEntryPerConsoleType(rates);
        rates.forEach(rate -> apply(rate.consoleType(), rate));
        return list();
    }

    @PutMapping("/{consoleType}")
    @PreAuthorize(Roles.ADMIN)
    @Operation(summary = "Set one console's rates (Admin)")
    public PricingView update(@PathVariable ConsoleType consoleType,
                              @Valid @RequestBody UpdatePricingRequest request) {
        if (request.consoleType() != null && request.consoleType() != consoleType) {
            throw ValidationFailedException.onField("consoleType",
                    "consoleType in the body must match the path");
        }
        return view(apply(consoleType, request));
    }

    private Pricing apply(ConsoleType consoleType, UpdatePricingRequest request) {
        return pricing.update(consoleType, request.perHour(), request.perHalfHour(),
                request.morningDiscountPct(), request.morningStart(), request.morningEnd());
    }

    private PricingView view(Pricing rate) {
        return PricingView.of(rate, pricing.blockPrice(rate.getConsoleType()));
    }

    private static void requireOneEntryPerConsoleType(List<UpdatePricingRequest> rates) {
        if (rates == null || rates.isEmpty()) {
            throw ValidationFailedException.onField("rates", "at least one rate is required");
        }
        Set<ConsoleType> seen = new HashSet<>();
        for (UpdatePricingRequest rate : rates) {
            if (rate.consoleType() == null) {
                throw ValidationFailedException.onField("consoleType",
                        "consoleType is required on every entry");
            }
            if (!seen.add(rate.consoleType())) {
                throw ValidationFailedException.onField("consoleType",
                        "%s appears twice in the same update".formatted(rate.consoleType()));
            }
        }
    }
}
