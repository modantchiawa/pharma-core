package com.pharmacore.demo;

import com.pharmacore.catalog.persistence.ProductEntity;
import com.pharmacore.control.application.FourEyesService;
import com.pharmacore.identity.application.LocalAuthService;
import com.pharmacore.identity.application.UserManagementService;
import com.pharmacore.identity.persistence.AppUserRepository;
import com.pharmacore.identity.persistence.RoleRepository;
import com.pharmacore.inventory.application.StockCountService;
import com.pharmacore.inventory.application.StockService;
import com.pharmacore.inventory.domain.WriteOffKind;
import com.pharmacore.sales.application.CashSessionService;
import com.pharmacore.sales.application.SaleManagementService;
import com.pharmacore.sales.application.SaleRecordingService;
import com.pharmacore.sales.domain.PaymentMethod;
import com.pharmacore.sales.domain.SaleDraft;
import com.pharmacore.sales.domain.SaleOrigin;
import com.pharmacore.sales.domain.SaleOutcome;
import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.RequestContext;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.context.TenantId;
import com.pharmacore.shared.security.TenantUserLoader;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Internal control in the demonstration, for the first pharmacy only, through the regular
 * services (same rules, same alerts): a team of four, a pharmacist adjoint who cancels paid
 * sales well more often than the others (always under the four-eyes threshold), a few declared
 * write-offs (one theft signed by the owner), yesterday's "contrôle du jour" short of two
 * tramadol in the safe, a sale at 23:20 two days ago, a drawer closed 2 500 F short yesterday.
 * Past events are dated back as the order follow-up is.
 */
@Component
@ConditionalOnProperty(prefix = "pharmacore.demo", name = "enabled", havingValue = "true")
class DemoInternalControl {

    static final String ADJOINT_EMAIL = "adjoint@sahel.demo";
    static final String PREPARATEUR_EMAIL = "preparatrice@sahel.demo";
    private static final DateTimeFormatter LABEL_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final DemoProperties demo;
    private final AppUserRepository users;
    private final RoleRepository roles;
    private final UserManagementService userManagement;
    private final LocalAuthService auth;
    private final TenantUserLoader loader;
    private final StockService stock;
    private final StockCountService counts;
    private final SaleRecordingService sales;
    private final SaleManagementService saleManagement;
    private final CashSessionService cashSessions;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    DemoInternalControl(DemoProperties demo, AppUserRepository users, RoleRepository roles,
                        UserManagementService userManagement, LocalAuthService auth, TenantUserLoader loader,
                        StockService stock, StockCountService counts, SaleRecordingService sales,
                        SaleManagementService saleManagement, CashSessionService cashSessions, JdbcClient jdbc,
                        PlatformTransactionManager transactionManager, Clock clock) {
        this.demo = demo;
        this.users = users;
        this.roles = roles;
        this.userManagement = userManagement;
        this.auth = auth;
        this.loader = loader;
        this.stock = stock;
        this.counts = counts;
        this.sales = sales;
        this.saleManagement = saleManagement;
        this.cashSessions = cashSessions;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** @param products the demo catalogue, in the order of {@link DemoDataSeeder#DRUGS} */
    void seed(TenantId tenant, String ownerEmail, String cashierEmail, List<ProductEntity> products, LocalDate today) {
        tx.executeWithoutResult(status -> {
            register(ADJOINT_EMAIL, "Dr Ibrahim Souley", "ADJOINT");
            register(PREPARATEUR_EMAIL, "Rakia Hamani", "PREPARATEUR");
        });
        loader.evictAll();
        var owner = tenantUser(tenant, ownerEmail);
        var adjoint = tenantUser(tenant, ADJOINT_EMAIL);
        var preparatrice = tenantUser(tenant, PREPARATEUR_EMAIL);
        var cashier = tenantUser(tenant, cashierEmail);
        var ownerSigns = new FourEyesService.Credentials(ownerEmail, demo.pin());
        var adjointSigns = new FourEyesService.Credentials(ADJOINT_EMAIL, demo.pin());

        TenantContext.run(adjoint, () -> adjointSales(products, today));
        TenantContext.run(adjoint, () -> {
            writeOff(product(products, "VITAMINE C"), WriteOffKind.PERIME, 3, "Boîtes périmées trouvées au rayon", null);
            writeOff(product(products, "INSULATARD"), WriteOffKind.PERTE, 1, "Flacon introuvable au frigo", null);
            // 2 × 5 005 F at cost: above the threshold, the owner signs on the adjoint's terminal
            writeOff(product(products, "AUGMENTIN"), WriteOffKind.VOL, 2, "Boîtes manquantes après le rush", ownerSigns);
        });
        TenantContext.run(owner, () -> writeOff(product(products, "SRO"), WriteOffKind.CASSE, 2,
                "Sachets déchirés à la réception", null));

        TenantContext.run(owner, () -> outsideHoursSale(products, today));
        TenantContext.run(cashier, () -> shortDrawer(today));
        dailyCounts(owner, preparatrice, adjointSigns, products, today);
    }

    // ------------------------------------------------------------------ staff

    private void register(String email, String name, String roleCode) {
        UUID role = roles.findByCode(roleCode).orElseThrow().id();
        userManagement.register(email, name, Set.of(role));
        var user = users.findByEmailIgnoreCase(email).orElseThrow();
        user.setPassword(auth.encode(demo.accountPassword()), false, clock.instant());
        user.setPin(auth.encode(demo.pin()));
    }

    // ------------------------------------------------------------------ sales and cancellations

    /**
     * Two sales an evening for two weeks; six of them, paid in cash, are cancelled 25 minutes
     * later, each under the 5 000 F four-eyes threshold: no single one stands out, the rate does.
     */
    private void adjointSales(List<ProductEntity> products, LocalDate today) {
        Random random = new Random(4242);
        List<ProductEntity> cheap = List.of(product(products, "PARACETAMOL 500MG"), product(products, "IBUPROFENE"),
                product(products, "SRO"), product(products, "VITAMINE C"), product(products, "DICLOFENAC"),
                product(products, "DOLIPRANE"));
        UUID adjointId = TenantContext.requireTenantUser().userId();
        int ticket = 1;
        List<UUID> cancelled = new ArrayList<>();
        for (int daysAgo = 13; daysAgo >= 1; daysAgo--) {
            for (int n = 0; n < 2; n++) {
                ProductEntity product = cheap.get(random.nextInt(cheap.size()));
                int quantity = 1 + random.nextInt(2);
                long net = product.publicPriceCents() * quantity;
                Instant at = today.minusDays(daysAgo).atTime(LocalTime.of(17 + n, 5 + random.nextInt(50)))
                        .atZone(DemoDataSeeder.ZONE).toInstant();
                var outcome = sales.record(new SaleDraft(UUID.randomUUID(), "DEMO-2",
                        "DEMO-2-" + String.format("%05d", ticket++), at, SaleOrigin.ONLINE, adjointId, null,
                        List.of(new SaleDraft.Line(product.id(), quantity, product.publicPriceCents(), 0)),
                        List.of(new SaleDraft.Payment(PaymentMethod.CASH, net, null, null, null, null)), null));
                if (outcome instanceof SaleOutcome.Accepted accepted && (daysAgo % 2 == 0) && n == 1) {
                    cancelled.add(accepted.saleId());
                }
            }
        }
        List<String> reasons = List.of("Client a changé d'avis", "Erreur de produit", "Doublon de ticket",
                "Client parti sans payer", "Erreur de saisie", "Produit refusé");
        for (int i = 0; i < cancelled.size(); i++) {
            saleManagement.cancel(cancelled.get(i), reasons.get(i % reasons.size()), null, null);
        }
        tx.executeWithoutResult(status -> jdbc.sql("""
                        UPDATE sale SET cancelled_at = occurred_at + interval '25 minutes'
                        WHERE id = ANY(:ids)""")
                .param("ids", cancelled.toArray(UUID[]::new)).update());
    }

    /** Two days ago at 23:20, outside the activity hours and on a night without guard duty. */
    private void outsideHoursSale(List<ProductEntity> products, LocalDate today) {
        ProductEntity product = product(products, "DOLIPRANE");
        Instant at = today.minusDays(2).atTime(23, 20).atZone(DemoDataSeeder.ZONE).toInstant();
        var outcome = sales.record(new SaleDraft(UUID.randomUUID(), DemoDataSeeder.TERMINAL, "DEMO-1-N0001", at,
                SaleOrigin.ONLINE, users.findByEmailIgnoreCase(ADJOINT_EMAIL).orElseThrow().id(), null,
                List.of(new SaleDraft.Line(product.id(), 2, product.publicPriceCents(), 0)),
                List.of(new SaleDraft.Payment(PaymentMethod.CASH, 2 * product.publicPriceCents(), null, null, null,
                        null)), null));
        if (outcome instanceof SaleOutcome.Accepted accepted) {
            backdateAlerts(accepted.saleId(), at);
        }
    }

    /** The cashier's drawer of yesterday on the second terminal, closed 2 500 F short. */
    private void shortDrawer(LocalDate today) {
        var opened = cashSessions.openSession("DEMO-2", 30_000);
        cashSessions.closeSession(opened.sessionId(), new CashSessionService.Closing(List.of(), 27_500L, List.of(),
                "Manque constaté au comptage"));
        Instant openedAt = today.minusDays(1).atTime(8, 0).atZone(DemoDataSeeder.ZONE).toInstant();
        Instant closedAt = today.minusDays(1).atTime(20, 10).atZone(DemoDataSeeder.ZONE).toInstant();
        tx.executeWithoutResult(status -> jdbc.sql("""
                        UPDATE cash_session SET opened_at = :opened, closed_at = :closed WHERE id = :id""")
                .param("opened", Timestamp.from(openedAt)).param("closed", Timestamp.from(closedAt))
                .param("id", opened.sessionId()).update());
        backdateAlerts(opened.sessionId(), closedAt);
    }

    // ------------------------------------------------------------------ stock

    private void writeOff(ProductEntity product, WriteOffKind kind, int quantity, String reason,
                          FourEyesService.Credentials approver) {
        UUID lot = tx.execute(status -> jdbc.sql("""
                        SELECT l.id FROM stock_lot l JOIN stock s ON s.id = l.stock_id
                        WHERE s.product_id = :product AND l.quantity >= :quantity
                        ORDER BY l.expiry_date LIMIT 1""")
                .param("product", product.id()).param("quantity", quantity)
                .query(UUID.class).single());
        stock.writeOff(lot, kind, quantity, reason, approver);
    }

    /**
     * Yesterday's control (tramadol and four others) found two capsules missing in the safe. The
     * count itself is recorded now, after the seeded receipts, so the narcotics register runs in
     * order; only its daily-control row is dated yesterday. Today's control is drawn at first login.
     */
    private void dailyCounts(RequestContext owner, RequestContext preparatrice, FourEyesService.Credentials approver,
                             List<ProductEntity> products, LocalDate today) {
        ProductEntity tramadol = product(products, "TRAMADOL");
        List<ProductEntity> drawn = List.of(tramadol, product(products, "INSULATARD"),
                product(products, "AUGMENTIN"), product(products, "SALBUTAMOL"), product(products, "COARTEM"));
        List<String> reasons = List.of("stupéfiant · rangé au coffre · jamais compté",
                "valeur élevée au coût · jamais compté",
                "1 sortie(s) ou ajustement(s) manuel(s) récent(s) · valeur élevée au coût",
                "jamais compté", "forte rotation (2,1/j) · jamais compté");
        LocalDate day = today.minusDays(1);
        Map<UUID, Integer> gaps = Map.of(tramadol.id(), -2);

        String label = "Contrôle du jour du " + LABEL_DATE.format(day);
        var started = TenantContext.call(preparatrice, () -> counts.startForProducts(label,
                drawn.stream().map(ProductEntity::id).toList()));
        var frozen = TenantContext.call(owner, () -> counts.get(started.id()));
        // the gap is found on one lot of the product, the other lots are as expected
        Set<UUID> adjusted = new HashSet<>();
        TenantContext.run(preparatrice, () -> frozen.lines().forEach(line -> counts.count(started.id(), line.id(),
                Math.max(0, line.expectedQuantity()
                        + (adjusted.add(line.productId()) ? gaps.getOrDefault(line.productId(), 0) : 0)))));
        TenantContext.run(owner, () -> counts.validate(started.id(), "Contrôle tournant", approver));

        UUID tenant = TenantContext.call(owner, () -> TenantContext.requireTenant().value());
        UUID dailyId = UUID.randomUUID();
        TenantContext.run(owner, () -> tx.executeWithoutResult(status -> {
            jdbc.sql("""
                            INSERT INTO control_daily_count (id, tenant_id, count_date, stock_count_id, selected_at,
                                                             started_by, started_at)
                            SELECT :daily, :tenant, :day, c.id, :selected, c.started_by, c.started_at
                            FROM stock_count c WHERE c.id = :count""")
                    .param("daily", dailyId).param("tenant", tenant).param("day", Date.valueOf(day))
                    .param("selected", Timestamp.from(day.atTime(7, 45).atZone(DemoDataSeeder.ZONE).toInstant()))
                    .param("count", started.id()).update();
            for (int i = 0; i < drawn.size(); i++) {
                jdbc.sql("""
                                INSERT INTO control_daily_count_item (id, tenant_id, daily_count_id, product_id, rank,
                                                                      score, reasons)
                                VALUES (:id, :tenant, :daily, :product, :rank, :score, :reasons)""")
                        .param("id", UUID.randomUUID()).param("tenant", tenant).param("daily", dailyId)
                        .param("product", drawn.get(i).id()).param("rank", i + 1).param("score", 78 - i * 11)
                        .param("reasons", reasons.get(i)).update();
            }
        }));
    }

    private void backdateAlerts(UUID resourceId, Instant at) {
        tx.executeWithoutResult(status -> jdbc.sql("""
                        UPDATE control_alert SET occurred_at = :at, created_at = :at WHERE resource_id = :id""")
                .param("at", Timestamp.from(at)).param("id", resourceId).update());
    }

    private static ProductEntity product(List<ProductEntity> products, String designationStart) {
        return products.stream().filter(p -> p.designation().startsWith(designationStart)).findFirst().orElseThrow();
    }

    private RequestContext tenantUser(TenantId tenant, String email) {
        Supplier<Actor.TenantUser> load = () -> {
            var user = users.findByEmailIgnoreCase(email).orElseThrow();
            return loader.load(tenant, user.externalSubject());
        };
        var system = RequestContext.of(Actor.SystemProcess.forTenant("demo-seed", tenant));
        Actor.TenantUser actor = TenantContext.call(system, () -> tx.execute(status -> load.get()));
        return RequestContext.of(actor);
    }
}
