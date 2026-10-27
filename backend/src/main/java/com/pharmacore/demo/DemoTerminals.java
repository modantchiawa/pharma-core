package com.pharmacore.demo;

import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.RequestContext;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.context.TenantId;
import com.pharmacore.shared.runtime.NodeProperties;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cash registers of the demo pharmacies, one per synchronization state, so that the supervision
 * screens tell a story right after login: {@code DEMO-1} (the register holding the seeded sales and
 * the open cash session) is synchronized, {@code CAISSE-2} holds three sales recorded offline,
 * {@code CAISSE-3} has been switched off for two days and {@code BUREAU} never sent a sale. The
 * registers that are "on" keep beating every minute, as real ones do, so the picture stays the
 * same however long the demo runs.
 */
@Component
@ConditionalOnProperty(prefix = "pharmacore.demo", name = "enabled", havingValue = "true")
class DemoTerminals {

    static final String VERSION = "demo";

    /**
     * @param silence     time since the last heartbeat at seeding; live registers keep beating
     * @param lastSyncAgo time since the last replay of offline sales, {@code null} if never
     */
    record DemoTerminal(String slug, String terminalId, int pendingSales, Duration silence, Duration lastSyncAgo,
                        boolean live) {
    }

    static final List<DemoTerminal> TERMINALS = List.of(
            new DemoTerminal("sahel", DemoDataSeeder.TERMINAL, 0, Duration.ofSeconds(40), Duration.ofMinutes(2), true),
            new DemoTerminal("sahel", "CAISSE-2", 3, Duration.ofSeconds(25), Duration.ofHours(3), true),
            new DemoTerminal("sahel", "CAISSE-3", 1, Duration.ofDays(2), Duration.ofDays(2).plusMinutes(20), false),
            new DemoTerminal("sahel", "BUREAU", 0, Duration.ofSeconds(50), null, true),
            new DemoTerminal("lacouroussou", DemoDataSeeder.TERMINAL, 0, Duration.ofSeconds(35), Duration.ofMinutes(4),
                    true),
            new DemoTerminal("lacouroussou", "CAISSE-2", 0, Duration.ofHours(3), Duration.ofHours(3).plusMinutes(5),
                    false));

    private final NodeProperties node;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    DemoTerminals(NodeProperties node, JdbcClient jdbc, PlatformTransactionManager transactionManager, Clock clock) {
        this.node = node;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** Called by the seeder inside the pharmacy's context and transaction. */
    static void seed(JdbcClient jdbc, TenantId tenant, String slug, Instant now) {
        for (DemoTerminal terminal : TERMINALS) {
            if (!terminal.slug().equals(slug)) {
                continue;
            }
            jdbc.sql("""
                            INSERT INTO terminal_heartbeat (tenant_id, terminal_id, app_version, pending_sales,
                                                            rejected_sales, last_sync_at, last_seen_at)
                            VALUES (:tenant, :terminal, :version, :pending, 0, :lastSync, :lastSeen)
                            ON CONFLICT (tenant_id, terminal_id) DO NOTHING""")
                    .param("tenant", tenant.value())
                    .param("terminal", terminal.terminalId())
                    .param("version", VERSION)
                    .param("pending", terminal.pendingSales())
                    .param("lastSync", terminal.lastSyncAgo() == null ? null
                            : Timestamp.from(now.minus(terminal.lastSyncAgo())))
                    .param("lastSeen", Timestamp.from(now.minus(terminal.silence())))
                    .update();
        }
    }

    /** The live demo registers beat like the PWA does (every minute); the synchronized one replays too. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    void pulse() {
        if (node.isHub()) {
            return;
        }
        Instant now = clock.instant();
        var context = RequestContext.of(Actor.SystemProcess.crossTenant("demo-terminals"));
        TenantContext.run(context, () -> tx.executeWithoutResult(status -> {
            for (DemoTerminal terminal : TERMINALS) {
                if (!terminal.live()) {
                    continue;
                }
                jdbc.sql("""
                                UPDATE terminal_heartbeat h
                                SET last_seen_at = :now,
                                    last_sync_at = CASE WHEN h.pending_sales = 0 AND h.last_sync_at IS NOT NULL
                                                        THEN :lastSync ELSE h.last_sync_at END
                                FROM tenant t
                                WHERE t.id = h.tenant_id AND t.slug = :slug AND h.terminal_id = :terminal""")
                        .param("now", Timestamp.from(now))
                        .param("lastSync", Timestamp.from(now.minus(terminal.lastSyncAgo() == null
                                ? Duration.ZERO : terminal.lastSyncAgo())))
                        .param("slug", terminal.slug())
                        .param("terminal", terminal.terminalId())
                        .update();
            }
        }));
    }
}
