package dev.gamersden.catalog.web;

import dev.gamersden.catalog.domain.ItemCategory;
import dev.gamersden.catalog.domain.ItemService;
import dev.gamersden.common.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * {@code /items} — every operator reads the menu to sell from it; only Manager+ edits it
 * (api-contract.md §1, permission matrix: "Menu &amp; stock CRUD" — Admin and Manager, cashier
 * ✗). The guard is the {@code @PreAuthorize} here, not the sidebar: a cashier writing gets the
 * 403 envelope.
 */
@RestController
@RequestMapping("/items")
@Tag(name = "Menu & stock")
public class ItemController {

    private final ItemService items;

    public ItemController(ItemService items) {
        this.items = items;
    }

    @GetMapping
    @PreAuthorize(Roles.ANY_STAFF)
    @Operation(summary = "The menu",
            description = "Grouped by category, alphabetical inside it. The POS passes "
                    + "active=true; S10's editor passes nothing and sees retired rows too.")
    public List<ItemView> list(@RequestParam(required = false) Boolean active,
                               @RequestParam(required = false) ItemCategory category) {
        return items.menu(active, category).stream().map(ItemView::of).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize(Roles.ANY_STAFF)
    @Operation(summary = "One menu row with its derived stock state")
    public ItemView get(@PathVariable Long id) {
        return ItemView.of(items.stockOf(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.MANAGER_PLUS)
    @Operation(summary = "Add a menu item (Manager+)",
            description = "409 DUPLICATE_NAME when the name is taken. An opening stock lands as "
                    + "an INITIAL stock_movements row.")
    public ItemView create(@Valid @RequestBody CreateItemRequest request) {
        Long id = items.create(request.name(), request.category(), request.price(),
                request.stockOrZero(), request.reorderAtOrZero()).getId();
        return ItemView.of(items.stockOf(id));
    }

    @PatchMapping("/{id}")
    @PreAuthorize(Roles.MANAGER_PLUS)
    @Operation(summary = "Edit a menu item or correct its stock (Manager+)",
            description = "stock is the absolute counted figure; the difference is audited as one "
                    + "signed MANUAL_ADJUST movement. 409 DUPLICATE_NAME on a taken name.")
    public ItemView update(@PathVariable Long id, @Valid @RequestBody UpdateItemRequest request) {
        items.update(id, request.name(), request.category(), request.price(), request.stock(),
                request.reorderAt(), request.active());
        return ItemView.of(items.stockOf(id));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(Roles.MANAGER_PLUS)
    @Operation(summary = "Take an item off the menu (Manager+)",
            description = "Deleted outright while nothing points at it; once it has sales or "
                    + "stock history the row is deactivated instead, so the audit survives.")
    public void delete(@PathVariable Long id) {
        items.delete(id);
    }
}
