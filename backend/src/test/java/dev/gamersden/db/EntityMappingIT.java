package dev.gamersden.db;

import dev.gamersden.auth.repo.StaffRepository;
import dev.gamersden.catalog.domain.Cart;
import dev.gamersden.catalog.domain.CartLine;
import dev.gamersden.catalog.domain.CartLineId;
import dev.gamersden.catalog.domain.Item;
import dev.gamersden.catalog.domain.ItemCategory;
import dev.gamersden.catalog.repo.CartLineRepository;
import dev.gamersden.catalog.repo.CartRepository;
import dev.gamersden.catalog.repo.ItemRepository;
import dev.gamersden.common.idempotency.IdempotencyKey;
import dev.gamersden.common.idempotency.IdempotencyKeyRepository;
import dev.gamersden.member.domain.Member;
import dev.gamersden.member.repo.MemberRepository;
import dev.gamersden.printing.domain.PrintJob;
import dev.gamersden.printing.domain.PrintJobStatus;
import dev.gamersden.printing.domain.PrintJobType;
import dev.gamersden.printing.repo.PrintJobRepository;
import dev.gamersden.settings.domain.TerminalSettings;
import dev.gamersden.settings.domain.Theme;
import dev.gamersden.settings.repo.TerminalSettingsRepository;
import dev.gamersden.support.AbstractIntegrationTest;
import dev.gamersden.sync.domain.SyncOutboxEntry;
import dev.gamersden.sync.repo.SyncOutboxRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code validate} only proves the column types line up. These round-trips prove the awkward ones
 * actually read and write: {@code TEXT[]}, {@code JSONB}, {@code BYTEA}, a UUID key, a composite
 * key, and the DB-generated {@code created_at} that keeps time server-side (invariant §5.1).
 *
 * <p>Transactional and therefore rolled back: the suite shares one container, so these writes must
 * not leak into the seed assertions in {@link BaselineSchemaIT}.
 */
@Transactional
class EntityMappingIT extends AbstractIntegrationTest {

    @PersistenceContext
    EntityManager em;

    @Autowired
    MemberRepository members;

    @Autowired
    SyncOutboxRepository outbox;

    @Autowired
    IdempotencyKeyRepository idempotencyKeys;

    @Autowired
    TerminalSettingsRepository terminalSettings;

    @Autowired
    PrintJobRepository printJobs;

    @Autowired
    ItemRepository items;

    @Autowired
    CartRepository carts;

    @Autowired
    CartLineRepository cartLines;

    @Autowired
    StaffRepository staff;

    @Test
    void memberGamesRoundTripThroughTheTextArray() {
        Member member = new Member("Rifat Hasan", "+8801712448190");
        member.setGames(new String[] {"FIFA 25", "Tekken 8"});
        member.setPreferredConsole("PS5");

        Long id = members.saveAndFlush(member).getId();
        em.clear();

        Member reloaded = members.findById(id).orElseThrow();
        assertThat(reloaded.getGames()).containsExactly("FIFA 25", "Tekken 8");
        assertThat(reloaded.getWallet()).isZero();
        assertThat(reloaded.getPoints()).isZero();
        assertThat(reloaded.getCreatedAt()).isNotNull();
    }

    @Test
    void outboxOpRoundTripsThroughJsonb() {
        SyncOutboxEntry entry = outbox.saveAndFlush(
                new SyncOutboxEntry("transactions", "{\"id\":1,\"kind\":\"SETTLE\"}"));
        em.clear();

        SyncOutboxEntry reloaded = outbox.findById(entry.getId()).orElseThrow();
        assertThat(reloaded.getOp()).contains("\"kind\": \"SETTLE\"");
        assertThat(reloaded.getPushedAt()).isNull();
        assertThat(outbox.findByPushedAtIsNullOrderByIdAsc()).contains(reloaded);
    }

    @Test
    void idempotencyKeyRoundTripsThroughUuidAndJsonb() {
        UUID key = UUID.fromString("6f1c4a2e-0f3e-4d5b-8a1c-2b3d4e5f6a7b");
        idempotencyKeys.saveAndFlush(new IdempotencyKey(key, "sha256:abc", "{\"transactionId\":42}", 201));
        em.clear();

        IdempotencyKey reloaded = idempotencyKeys.findById(key).orElseThrow();
        assertThat(reloaded.getStatusCode()).isEqualTo(201);
        assertThat(reloaded.getResponseBody()).contains("42");
        assertThat(reloaded.getCreatedAt()).isNotNull();
    }

    @Test
    void loginBackgroundRoundTripsThroughBytea() {
        TerminalSettings settings = new TerminalSettings("T1");
        settings.setLoginBg(new byte[] {(byte) 0x89, 'P', 'N', 'G'});

        terminalSettings.saveAndFlush(settings);
        em.clear();

        TerminalSettings reloaded = terminalSettings.findById("T1").orElseThrow();
        assertThat(reloaded.getTheme()).isEqualTo(Theme.DARK);
        assertThat(reloaded.getAccent()).isEqualTo("#ec3013");
        assertThat(reloaded.getReceiptCopies()).isEqualTo(1);
        assertThat(reloaded.getLoginBg()).containsExactly((byte) 0x89, 'P', 'N', 'G');
    }

    @Test
    void renderedPrintBytesRoundTripAndTheQueueViewFindsThem() {
        Long operatorId = staff.findByName("Admin").orElseThrow().getId();
        byte[] escpos = {0x1B, 0x40, 'G', 'D'};

        PrintJob job = printJobs.saveAndFlush(new PrintJob(
                PrintJobType.PLAY_TICKET, 7L, "usb:001", operatorId, escpos, "TOKEN 12"));
        em.clear();

        PrintJob reloaded = printJobs.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getRendered()).containsExactly(escpos);
        assertThat(reloaded.getRenderedText()).isEqualTo("TOKEN 12");
        assertThat(reloaded.getStatus()).isEqualTo(PrintJobStatus.QUEUED);
        assertThat(reloaded.isReprint()).isFalse();
        assertThat(printJobs.findByStatusInOrderByIdAsc(
                java.util.List.of(PrintJobStatus.QUEUED, PrintJobStatus.FAILED))).contains(reloaded);
    }

    @Test
    void cartLinesUseTheCompositeKey() {
        Item item = items.saveAndFlush(new Item("Cold Coffee", ItemCategory.BEVERAGE, 150));
        Cart cart = carts.saveAndFlush(new Cart(null)); // counter cart

        cartLines.saveAndFlush(new CartLine(cart.getId(), item.getId(), 2, item.getPrice()));
        em.clear();

        CartLine reloaded = cartLines.findById(new CartLineId(cart.getId(), item.getId())).orElseThrow();
        assertThat(reloaded.getQty()).isEqualTo(2);
        assertThat(reloaded.getUnitPrice()).isEqualTo(150);
        assertThat(cartLines.findByIdCartId(cart.getId())).hasSize(1);
    }

    @Test
    void renderedTextSurvivesNonAsciiPreview() {
        Long operatorId = staff.findByName("Admin").orElseThrow().getId();
        String preview = "TOTAL ৳1,240";

        PrintJob job = printJobs.saveAndFlush(new PrintJob(PrintJobType.RECEIPT, 1L, "usb:001", operatorId,
                preview.getBytes(StandardCharsets.UTF_8), preview));

        assertThat(printJobs.findById(job.getId()).orElseThrow().getRenderedText()).isEqualTo(preview);
    }
}
