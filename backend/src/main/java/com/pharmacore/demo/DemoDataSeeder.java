package com.pharmacore.demo;

import com.pharmacore.catalog.application.CatalogService;
import com.pharmacore.catalog.domain.Cip13;
import com.pharmacore.catalog.domain.DrugListing;
import com.pharmacore.catalog.persistence.ProductEntity;
import com.pharmacore.catalog.persistence.ProductEntity.ProductData;
import com.pharmacore.customers.application.CustomerService;
import com.pharmacore.customers.persistence.CustomerEntity;
import com.pharmacore.guard.application.GuardScheduleService;
import com.pharmacore.guard.persistence.GuardType;
import com.pharmacore.identity.application.LocalAuthService;
import com.pharmacore.identity.application.UserManagementService;
import com.pharmacore.identity.persistence.AppUserRepository;
import com.pharmacore.identity.persistence.RoleRepository;
import com.pharmacore.insurance.application.InsurerService;
import com.pharmacore.insurance.domain.InsurerKind;
import com.pharmacore.insurance.persistence.InsurancePolicyEntity;
import com.pharmacore.insurance.persistence.InsurerEntity;
import com.pharmacore.inventory.application.StockService;
import com.pharmacore.network.application.NetworkDirectoryService;
import com.pharmacore.network.application.PublicSharingService;
import com.pharmacore.procurement.application.ProcurementService;
import com.pharmacore.procurement.persistence.SupplierEntity;
import com.pharmacore.sales.application.CashSessionService;
import com.pharmacore.sales.application.SaleRecordingService;
import com.pharmacore.sales.domain.PaymentMethod;
import com.pharmacore.sales.domain.SaleDraft;
import com.pharmacore.sales.domain.SaleOrigin;
import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.RequestContext;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.context.TenantId;
import com.pharmacore.shared.runtime.NodeProperties;
import com.pharmacore.shared.security.TenantUserLoader;
import com.pharmacore.tenant.application.TenantProvisioningService;
import com.pharmacore.tenant.persistence.PharmacyConfigEntity.PublicSharing;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Builds the demonstration dataset through the regular services (same rules, same audit as a
 * real pharmacy): two Niamey pharmacies in one guard network, a drug reference with DCI, stock
 * with lots about to expire, customers with credit, suppliers, an insurer and a policy, two weeks
 * of sales, a public directory listing and guards for the week. Idempotent: a pharmacy whose slug
 * already exists is left untouched.
 */
@Component
@ConditionalOnProperty(prefix = "pharmacore.demo", name = "enabled", havingValue = "true")
class DemoDataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
    static final ZoneId ZONE = ZoneId.of("Africa/Niamey");
    static final String TERMINAL = "DEMO-1";

    record Pharmacy(String slug, String name, String ownerEmail, String ownerName, String cashierEmail,
                    String cashierName, String phone, String whatsapp, String address, BigDecimal lat, BigDecimal lon) {
    }

    static final List<Pharmacy> PHARMACIES = List.of(
            new Pharmacy("sahel", "Pharmacie du Sahel", "titulaire@sahel.demo", "Dr Aïchatou Maïga",
                    "caisse@sahel.demo", "Issoufou Garba", "+22720730101", "+22790101010",
                    "Boulevard Mali Béro, Plateau, Niamey", new BigDecimal("13.515800"), new BigDecimal("2.112100")),
            new Pharmacy("lacouroussou", "Pharmacie Lacouroussou", "titulaire@lacouroussou.demo", "Dr Moussa Hamidou",
                    "caisse@lacouroussou.demo", "Mariama Seyni", "+22720730202", "+22790202020",
                    "Rond-point Lacouroussou, Niamey", new BigDecimal("13.521400"), new BigDecimal("2.098700")));

    record Drug(String designation, String dci, String dosage, String form, String laboratory, DrugListing listing,
                long priceCents) {
    }

    static final List<Drug> DRUGS = List.of(
            new Drug("PARACETAMOL 500MG CPR B/16", "PARACETAMOL", "500MG", "Comprimé", "Sanofi", DrugListing.NONE, 650),
            new Drug("DOLIPRANE 1000MG CPR B/8", "PARACETAMOL", "1000MG", "Comprimé", "Sanofi", DrugListing.NONE, 1_250),
            new Drug("PARACETAMOL BIOGARAN 1000MG CPR B/8", "PARACETAMOL", "1000MG", "Comprimé", "Biogaran", DrugListing.NONE, 850),
            new Drug("EFFERALGAN PEDIATRIQUE SIROP 90ML", "PARACETAMOL", "30MG/ML", "Sirop", "UPSA", DrugListing.NONE, 1_850),
            new Drug("AMOXICILLINE 500MG GEL B/12", "AMOXICILLINE", "500MG", "Gélule", "Denk", DrugListing.LIST_I, 1_750),
            new Drug("AMOXICILLINE 1G CPR B/14", "AMOXICILLINE", "1G", "Comprimé", "Sandoz", DrugListing.LIST_I, 3_500),
            new Drug("AUGMENTIN 1G CPR B/14", "AMOXICILLINE + ACIDE CLAVULANIQUE", "1G/125MG", "Comprimé", "GSK", DrugListing.LIST_I, 6_950),
            new Drug("COARTEM 80/480MG CPR B/6", "ARTEMETHER + LUMEFANTRINE", "80/480MG", "Comprimé", "Novartis", DrugListing.NONE, 2_600),
            new Drug("ARTEFAN 80/480MG CPR B/6", "ARTEMETHER + LUMEFANTRINE", "80/480MG", "Comprimé", "Ajanta", DrugListing.NONE, 1_800),
            new Drug("ARTESUNATE 60MG INJ", "ARTESUNATE", "60MG", "Injectable", "Guilin", DrugListing.LIST_I, 3_200),
            new Drug("METFORMINE 850MG CPR B/30", "METFORMINE", "850MG", "Comprimé", "Merck", DrugListing.LIST_I, 2_450),
            new Drug("GLUCOPHAGE 850MG CPR B/30", "METFORMINE", "850MG", "Comprimé", "Merck", DrugListing.LIST_I, 4_100),
            new Drug("AMLODIPINE 5MG CPR B/30", "AMLODIPINE", "5MG", "Comprimé", "Pfizer", DrugListing.LIST_I, 1_950),
            new Drug("AMLOR 5MG GEL B/30", "AMLODIPINE", "5MG", "Gélule", "Pfizer", DrugListing.LIST_I, 4_850),
            new Drug("OMEPRAZOLE 20MG GEL B/14", "OMEPRAZOLE", "20MG", "Gélule", "Zentiva", DrugListing.NONE, 2_150),
            new Drug("IBUPROFENE 400MG CPR B/20", "IBUPROFENE", "400MG", "Comprimé", "Mylan", DrugListing.NONE, 1_100),
            new Drug("SRO SACHETS B/10", "SELS DE REHYDRATATION ORALE", "", "Sachet", "ONPPC", DrugListing.NONE, 600),
            new Drug("ZINC 20MG CPR B/10", "ZINC", "20MG", "Comprimé", "ONPPC", DrugListing.NONE, 450),
            new Drug("VITAMINE C 500MG CPR B/30", "ACIDE ASCORBIQUE", "500MG", "Comprimé", "UPSA", DrugListing.NONE, 950),
            new Drug("CHLOROQUINE 100MG CPR B/20", "CHLOROQUINE", "100MG", "Comprimé", "Sanofi", DrugListing.LIST_I, 700),
            new Drug("COTRIMOXAZOLE 480MG CPR B/20", "SULFAMETHOXAZOLE + TRIMETHOPRIME", "480MG", "Comprimé", "Denk", DrugListing.LIST_I, 900),
            new Drug("TRAMADOL 50MG GEL B/20", "TRAMADOL", "50MG", "Gélule", "Grünenthal", DrugListing.STUPEFIANT, 2_400),
            new Drug("SALBUTAMOL 100µG AEROSOL", "SALBUTAMOL", "100µG", "Aérosol", "GSK", DrugListing.LIST_I, 3_800),
            new Drug("DICLOFENAC GEL 1% 50G", "DICLOFENAC", "1%", "Gel", "Novartis", DrugListing.NONE, 1_500),
            new Drug("MOUSTIQUAIRE IMPREGNEE 2 PLACES", "", "", "Accessoire", "Vestergaard", DrugListing.NONE, 3_500),
            new Drug("THERMOMETRE DIGITAL", "", "", "Accessoire", "Omron", DrugListing.NONE, 2_500));

    private final DemoProperties demo;
    private final NodeProperties node;
    private final TenantProvisioningService provisioning;
    private final AppUserRepository users;
    private final RoleRepository roles;
    private final UserManagementService userManagement;
    private final LocalAuthService auth;
    private final TenantUserLoader loader;
    private final CatalogService catalog;
    private final StockService stock;
    private final CustomerService customers;
    private final ProcurementService procurement;
    private final InsurerService insurers;
    private final PublicSharingService publicSharing;
    private final NetworkDirectoryService networks;
    private final GuardScheduleService guards;
    private final SaleRecordingService sales;
    private final CashSessionService cashSessions;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    DemoDataSeeder(DemoProperties demo, NodeProperties node, TenantProvisioningService provisioning,
                   AppUserRepository users, RoleRepository roles, UserManagementService userManagement,
                   LocalAuthService auth, TenantUserLoader loader, CatalogService catalog, StockService stock,
                   CustomerService customers, ProcurementService procurement, InsurerService insurers,
                   PublicSharingService publicSharing, NetworkDirectoryService networks, GuardScheduleService guards,
                   SaleRecordingService sales, CashSessionService cashSessions, JdbcClient jdbc,
                   PlatformTransactionManager transactionManager, Clock clock) {
        this.demo = demo;
        this.node = node;
        this.provisioning = provisioning;
        this.users = users;
        this.roles = roles;
        this.userManagement = userManagement;
        this.auth = auth;
        this.loader = loader;
        this.catalog = catalog;
        this.stock = stock;
        this.customers = customers;
        this.procurement = procurement;
        this.insurers = insurers;
        this.publicSharing = publicSharing;
        this.networks = networks;
        this.guards = guards;
        this.sales = sales;
        this.cashSessions = cashSessions;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order
    void seed() {
        if (node.isHub()) {
            log.warn("Mode démonstration ignoré : un hub ne porte pas de données de démonstration");
            return;
        }
        var platform = RequestContext.of(new Actor.PlatformOperator("demo-seed", "Démonstration"));
        List<UUID> tenantIds = new ArrayList<>();
        boolean created = false;
        for (Pharmacy pharmacy : PHARMACIES) {
            UUID existing = TenantContext.call(platform, () -> tenantIdBySlug(pharmacy.slug()));
            if (existing != null) {
                tenantIds.add(existing);
                continue;
            }
            created = true;
            UUID tenantId = TenantContext.call(platform, () -> provisioning.register(new TenantProvisioningService.NewPharmacy(
                    pharmacy.slug(), pharmacy.name() + " SARL", pharmacy.name(), "NE", "XOF", ZONE.getId(), "PRO",
                    clock.instant().plus(java.time.Duration.ofDays(365)),
                    new TenantProvisioningService.Owner(pharmacy.ownerEmail(), pharmacy.ownerName()))).id());
            tenantIds.add(tenantId);
            log.info("Démonstration : pharmacie {} créée", pharmacy.slug());
        }
        if (!created) {
            log.info("Démonstration : données déjà présentes, rien à charger");
            return;
        }
        List<ProductEntity> products = TenantContext.call(platform, this::seedCatalog);
        for (int i = 0; i < PHARMACIES.size(); i++) {
            seedPharmacy(PHARMACIES.get(i), new TenantId(tenantIds.get(i)), products, i);
        }
        seedNetwork(platform, tenantIds);
        log.info("Démonstration chargée : {} pharmacies, {} produits, comptes {} / mot de passe {} / PIN {}",
                PHARMACIES.size(), products.size(), PHARMACIES.stream().map(Pharmacy::ownerEmail).toList(),
                demo.accountPassword(), demo.pin());
    }

    // ------------------------------------------------------------------ platform side

    private UUID tenantIdBySlug(String slug) {
        return jdbc.sql("SELECT id FROM tenant WHERE slug = :slug").param("slug", slug).query(UUID.class)
                .optional().orElse(null);
    }

    private List<ProductEntity> seedCatalog() {
        List<ProductEntity> products = new ArrayList<>();
        for (int i = 0; i < DRUGS.size(); i++) {
            Drug drug = DRUGS.get(i);
            String first12 = "34009" + String.format("%07d", 9_000_000 + i);
            String cip13 = first12 + Cip13.checkDigit(first12);
            products.add(tx.execute(status -> catalog.upsert(cip13, new ProductData(null, drug.designation(),
                    drug.dci().isEmpty() ? null : drug.dci(), drug.form(), drug.dosage().isEmpty() ? null : drug.dosage(),
                    drug.laboratory(), null, drug.listing(), BigDecimal.ZERO, drug.priceCents(), null, null, true))));
        }
        return products;
    }

    private void seedNetwork(RequestContext platform, List<UUID> tenantIds) {
        UUID networkId = TenantContext.call(platform, () -> networks.createNetwork("Gardes Niamey · groupe I", "Niamey",
                tenantIds.getFirst(), 20, tenantIds));
        var coordinator = tenantUser(new TenantId(tenantIds.getFirst()), PHARMACIES.getFirst().ownerEmail());
        TenantContext.run(coordinator, () -> {
            networks.setGuardsPublication(networkId, true);
            LocalDate today = LocalDate.now(clock.withZone(ZONE));
            for (int day = 0; day < 7; day++) {
                LocalDate date = today.plusDays(day);
                UUID onDuty = tenantIds.get(day % tenantIds.size());
                guards.schedule(networkId, onDuty, GuardType.NIGHT,
                        date.atTime(LocalTime.of(20, 0)).atZone(ZONE).toInstant(),
                        date.plusDays(1).atTime(LocalTime.of(8, 0)).atZone(ZONE).toInstant(), "Garde de nuit");
            }
            LocalDate sunday = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
            guards.schedule(networkId, tenantIds.getLast(), GuardType.SUNDAY,
                    sunday.atTime(LocalTime.of(8, 0)).atZone(ZONE).toInstant(),
                    sunday.atTime(LocalTime.of(20, 0)).atZone(ZONE).toInstant(), "Garde du dimanche");
        });
    }

    // ------------------------------------------------------------------ pharmacy side

    private void seedPharmacy(Pharmacy pharmacy, TenantId tenant, List<ProductEntity> products, int index) {
        Random random = new Random(42L + index);
        LocalDate today = LocalDate.now(clock.withZone(ZONE));
        var owner = tenantUser(tenant, pharmacy.ownerEmail());
        TenantContext.run(owner, () -> {
            tx.executeWithoutResult(status -> {
                setCredentials(pharmacy.ownerEmail());
                UUID cashierRole = roles.findByCode("CAISSIER").orElseThrow().id();
                userManagement.register(pharmacy.cashierEmail(), pharmacy.cashierName(), Set.of(cashierRole));
                setCredentials(pharmacy.cashierEmail());
            });
            loader.evictAll();

            tx.executeWithoutResult(status -> publicSharing.update(new PublicSharing(true, true, pharmacy.lat(),
                    pharmacy.lon(), "Lun–Sam 8h–22h, dimanche 9h–13h", pharmacy.whatsapp())));
            tx.executeWithoutResult(status -> jdbc.sql("""
                            UPDATE pharmacy_config SET phone = :phone, address_line1 = :address, city = 'Niamey',
                                                       receipt_footer = 'Merci de votre visite. Conservez ce ticket.'
                            WHERE tenant_id = :tenant""")
                    .param("phone", pharmacy.phone()).param("address", pharmacy.address()).param("tenant", tenant.value())
                    .update());

            // suppliers
            UUID laborex = procurement.createSupplier(new SupplierEntity.Details("Laborex Niger", "Service commandes",
                    "+22720740000", "+22790740000", "commandes@laborex.ne", "Zone industrielle, Niamey", 1,
                    "LUN,MER,VEN", 250_000, "30 jours", null)).id();
            UUID copharni = procurement.createSupplier(new SupplierEntity.Details("Copharni", "M. Abdou",
                    "+22720750000", null, "ventes@copharni.ne", "Avenue du Progrès, Niamey", 2, "MAR,JEU",
                    100_000, "45 jours", null)).id();
            // the last product (a thermometer) is bought locally: no wholesaler sells it
            for (int i = 0; i < products.size() - 1; i++) {
                ProductEntity product = products.get(i);
                long purchase = Math.round(product.publicPriceCents() * 0.72 / 5) * 5;
                procurement.linkProduct(i % 3 == 0 ? copharni : laborex, product.id(), "REF-" + (1000 + i), purchase,
                        i % 5 == 0 ? 10 : 1, true);
            }

            // stock: everything in stock, some lots short-dated, a few products at zero for the substitution demo
            for (int i = 0; i < products.size(); i++) {
                ProductEntity product = products.get(i);
                long cost = Math.round(product.publicPriceCents() * 0.72 / 5) * 5;
                int base = 20 + random.nextInt(60);
                if ((i + index) % 7 == 3) {
                    continue; // out of stock: the counter proposes the equivalent
                }
                stock.receiveLot(product.id(), "L" + (2601 + i), today.plusMonths(14 + random.nextInt(20)), base, cost);
                if (i % 4 == 0) {
                    // short-dated lot large enough to survive two weeks of FEFO sales: shows in the alerts
                    stock.receiveLot(product.id(), "L" + (2501 + i), today.plusDays(25 + random.nextInt(50)), 30, cost);
                }
                stock.configure(product.id(), product.publicPriceCents(), 10, 40, "Rayon " + (char) ('A' + i % 6));
            }

            // customers, one with a credit account and an insurance policy
            var hadiza = customers.create(new CustomerEntity.Details("Mme Hadiza Moussa", "+22790" + (300000 + index),
                    null, "Quartier Plateau", 50_000, "Cliente régulière"));
            customers.create(new CustomerEntity.Details("M. Sani Abdoulaye", "+22796" + (400000 + index), null,
                    "Lacouroussou", 20_000, null));
            customers.create(new CustomerEntity.Details("École Les Flamboyants", "+22720" + (500000 + index),
                    "intendance@flamboyants.ne", "Yantala", 150_000, "Convention entreprise"));
            var insurer = insurers.create(new InsurerEntity.Details("NIA Assurances", InsurerKind.ASSURANCE, 80,
                    "Service santé", "+22720730000", "sante@niaassurances.ne", "Immeuble NIA, Niamey", 30));
            insurers.createPolicy(insurer.id(), hadiza.id(), new InsurancePolicyEntity.Details(
                    "NIA-2026-" + (1001 + index), "Hadiza Moussa", 80, 200_000L, today.minusMonths(2),
                    today.plusYears(1), true));

            // two weeks of sales: the cashier sells over-the-counter products, the pharmacist dispenses
            // the listed ones (the cashier role has no dispensing right)
            var cashier = users.findByEmailIgnoreCase(pharmacy.cashierEmail()).orElseThrow();
            var pharmacist = users.findByEmailIgnoreCase(pharmacy.ownerEmail()).orElseThrow();
            int rejected = 0;
            List<ProductEntity> sellable = new ArrayList<>();
            for (int i = 0; i < products.size(); i++) {
                if ((i + index) % 7 != 3) {
                    sellable.add(products.get(i));
                }
            }
            int ticket = 1;
            for (int daysAgo = 14; daysAgo >= 0; daysAgo--) {
                LocalDate day = today.minusDays(daysAgo);
                int count = 3 + random.nextInt(4);
                for (int n = 0; n < count; n++) {
                    Instant at = day.atTime(LocalTime.of(8 + random.nextInt(12), random.nextInt(60))).atZone(ZONE).toInstant();
                    if (at.isAfter(clock.instant())) {
                        continue;
                    }
                    List<SaleDraft.Line> lines = new ArrayList<>();
                    int lineCount = 1 + random.nextInt(3);
                    for (int l = 0; l < lineCount; l++) {
                        ProductEntity product = sellable.get(random.nextInt(sellable.size()));
                        if (product.listing() == DrugListing.STUPEFIANT
                                || lines.stream().anyMatch(line -> line.productId().equals(product.id()))) {
                            continue;
                        }
                        lines.add(new SaleDraft.Line(product.id(), 1 + random.nextInt(2), product.publicPriceCents(), 0));
                    }
                    if (lines.isEmpty()) {
                        continue;
                    }
                    long net = lines.stream().mapToLong(SaleDraft.Line::netCents).sum();
                    boolean listed = lines.stream().anyMatch(line -> products.stream()
                            .anyMatch(p -> p.id().equals(line.productId()) && p.listing() != DrugListing.NONE));
                    List<SaleDraft.Payment> payments = switch (random.nextInt(4)) {
                        case 0 -> List.of(new SaleDraft.Payment(PaymentMethod.MOBILE_MONEY, net, null,
                                "MM" + (100000 + ticket * 7 + index), "AIRTEL_MONEY", null));
                        case 1 -> List.of(new SaleDraft.Payment(PaymentMethod.MOBILE_MONEY, net, null,
                                "ZC" + (200000 + ticket * 3 + index), "ZAMANI_CASH", null));
                        default -> List.of(new SaleDraft.Payment(PaymentMethod.CASH, net, null, null, null, null));
                    };
                    var prescription = listed ? new SaleDraft.Prescription("Dr Oumarou Issa", "NE-1234", "P-" + ticket, null) : null;
                    var outcome = sales.record(new SaleDraft(UUID.randomUUID(), TERMINAL,
                            TERMINAL + "-" + String.format("%05d", ticket++), at, SaleOrigin.ONLINE,
                            listed ? pharmacist.id() : cashier.id(), null, lines, payments, prescription));
                    if (outcome instanceof com.pharmacore.sales.domain.SaleOutcome.Rejected r) {
                        rejected++;
                        log.warn("Démonstration : vente refusée ({}) {}", r.code(), r.violations());
                    }
                }
            }
            // one credit sale today for the regular customer
            ProductEntity first = sellable.getFirst();
            // credit is granted by the pharmacist (the cashier role cannot sell on credit)
            sales.record(new SaleDraft(UUID.randomUUID(), TERMINAL, TERMINAL + "-" + String.format("%05d", ticket),
                    clock.instant().minusSeconds(600), SaleOrigin.ONLINE, pharmacist.id(), hadiza.id(),
                    List.of(new SaleDraft.Line(first.id(), 1, first.publicPriceCents(), 0)),
                    List.of(new SaleDraft.Payment(PaymentMethod.CREDIT, first.publicPriceCents(), null, null, null, null)),
                    null));
            seedOrders(tenant, laborex, copharni, products, today);
            cashSessions.openSession(TERMINAL, 50_000);
            if (rejected > 0) {
                log.warn("Démonstration : {} vente(s) de {} refusée(s) par les règles métier", rejected, pharmacy.slug());
            }
        });
    }

    /**
     * Purchase orders at every stage of the follow-up: a draft below the supplier minimum, one sent
     * and due tomorrow, one two days late, one partly delivered (real receipt: lots and purchase
     * entry) and one fully delivered; then a few products fall under their minimum so that
     * tomorrow's sheet has lines to turn into orders.
     */
    private void seedOrders(TenantId tenant, UUID laborex, UUID copharni, List<ProductEntity> products,
                            LocalDate today) {
        Instant now = clock.instant();
        procurement.createOrder(copharni, orderLines(products, Map.of(0, 50, 18, 30, 3, 20)), null);

        procurement.send(procurement.createOrder(laborex, orderLines(products, Map.of(7, 60, 11, 30, 13, 20)), null)
                .id());

        UUID late = procurement.send(procurement.createOrder(copharni, orderLines(products, Map.of(6, 20, 9, 10)),
                "Relancer M. Abdou").id()).id();
        backdate(late, now.minus(java.time.Duration.ofDays(5)), today.minusDays(2));

        var partial = procurement.send(procurement.createOrder(laborex,
                orderLines(products, Map.of(1, 100, 4, 60, 14, 40, 22, 20)), null).id());
        backdate(partial.id(), now.minus(java.time.Duration.ofDays(3)), today);
        procurement.receive(receipt(laborex, partial, products, Map.of(1, 100, 4, 40, 14, 40),
                "LBX-F-" + partial.number(), today));

        var full = procurement.send(procurement.createOrder(copharni,
                orderLines(products, Map.of(15, 40, 12, 30, 21, 20)), null).id());
        backdate(full.id(), now.minus(java.time.Duration.ofDays(2)), today);
        procurement.receive(receipt(copharni, full, products, Map.of(15, 40, 12, 30, 21, 20),
                "CPH-" + full.number(), today));

        // tomorrow's sheet: products under a raised minimum, for both wholesalers and the thermometer
        Map<Integer, Integer> reorder = Map.of(5, 60, 8, 80, 19, 100, 20, 50, 0, 60, 18, 40, products.size() - 1, 10);
        tx.executeWithoutResult(status -> reorder.forEach((index, quantity) -> jdbc.sql("""
                        UPDATE stock SET reorder_threshold = 200, reorder_quantity = :quantity
                        WHERE tenant_id = :tenant AND product_id = :product""")
                .param("quantity", quantity).param("tenant", tenant.value())
                .param("product", products.get(index).id()).update()));
    }

    private static List<ProcurementService.OrderLineCommand> orderLines(List<ProductEntity> products,
                                                                        Map<Integer, Integer> quantityByIndex) {
        return quantityByIndex.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(e -> new ProcurementService.OrderLineCommand(products.get(e.getKey()).id(), e.getValue(), null,
                        e.getValue(), null))
                .toList();
    }

    private static ProcurementService.ReceiptCommand receipt(UUID supplierId, ProcurementService.OrderView order,
                                                             List<ProductEntity> products,
                                                             Map<Integer, Integer> quantityByIndex, String invoice,
                                                             LocalDate today) {
        Map<UUID, Integer> delivered = new java.util.HashMap<>();
        quantityByIndex.forEach((index, quantity) -> delivered.put(products.get(index).id(), quantity));
        String batch = order.number().substring(order.number().length() - 3);
        List<ProcurementService.ReceiptLineCommand> lines = order.lines().stream()
                .filter(line -> delivered.containsKey(line.productId()))
                .map(line -> new ProcurementService.ReceiptLineCommand(line.productId(), "R" + batch + "-" + line.lineNo(),
                        today.plusMonths(18), delivered.get(line.productId()), line.unitCostCents(), BigDecimal.ZERO,
                        null))
                .toList();
        return new ProcurementService.ReceiptCommand(supplierId, order.id(), invoice, today, null, lines);
    }

    /** Orders placed earlier than the seeding day, so the follow-up has a history. */
    private void backdate(UUID orderId, Instant placedAt, LocalDate expectedAt) {
        tx.executeWithoutResult(status -> jdbc.sql("""
                        UPDATE purchase_order SET created_at = :at::timestamptz, sent_at = :at::timestamptz + interval '2 hours',
                                                  expected_at = :expected
                        WHERE id = :id""")
                .param("at", java.sql.Timestamp.from(placedAt)).param("expected", java.sql.Date.valueOf(expectedAt))
                .param("id", orderId).update());
    }

    private void setCredentials(String email) {
        var user = users.findByEmailIgnoreCase(email).orElseThrow();
        user.setPassword(auth.encode(demo.accountPassword()), false, clock.instant());
        user.setPin(auth.encode(demo.pin()));
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
