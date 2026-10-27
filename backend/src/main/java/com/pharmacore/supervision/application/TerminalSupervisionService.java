package com.pharmacore.supervision.application;

import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.runtime.NodeProperties;
import com.pharmacore.shared.web.BusinessRuleException;
import com.pharmacore.shared.web.NotFoundException;
import com.pharmacore.supervision.domain.SyncSupervision;
import com.pharmacore.supervision.domain.TerminalSyncState;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who sells, from which register, and whether every register's sales reached the server: the
 * vendor's view of one pharmacy (hub, registers, accounts) and the pharmacy's view of its own
 * registers. Read-only apart from "forgetting" a register that no longer exists. Never exposes a
 * secret: password and PIN hashes are reduced to "set or not".
 */
@Service
public class TerminalSupervisionService {

    private final JdbcClient jdbc;
    private final NodeProperties node;
    private final AuditTrail audit;
    private final Clock clock;

    public TerminalSupervisionService(JdbcClient jdbc, NodeProperties node, AuditTrail audit, Clock clock) {
        this.jdbc = jdbc;
        this.node = node;
        this.audit = audit;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ vendor console

    /** Register health of every pharmacy, for the console list. */
    @Transactional(readOnly = true)
    public List<PharmacySyncHealth> syncHealth() {
        Instant now = clock.instant();
        return jdbc.sql("""
                        SELECT t.id, t.topology, hub.last_contact, r.reference,
                               coalesce(h.terminals, 0)     AS terminals,
                               coalesce(h.stale, 0)         AS stale,
                               coalesce(h.out_of_sync, 0)   AS out_of_sync,
                               coalesce(h.pending_sales, 0) AS pending_sales
                        FROM tenant t
                        LEFT JOIN LATERAL (
                            SELECT max(last_seen_at) AS last_contact FROM hub_node WHERE hub_node.tenant_id = t.id
                        ) hub ON true
                        CROSS JOIN LATERAL (
                            SELECT CASE WHEN :relay AND t.topology = 'HUB' AND hub.last_contact < :now::timestamptz
                                        THEN hub.last_contact ELSE :now::timestamptz END AS reference
                        ) r
                        LEFT JOIN LATERAL (
                            SELECT count(*) AS terminals,
                                   count(*) FILTER (WHERE last_seen_at < r.reference - make_interval(secs => :stale)) AS stale,
                                   count(*) FILTER (WHERE last_seen_at >= r.reference - make_interval(secs => :stale)
                                                      AND (pending_sales > 0 OR last_sync_at IS NULL)) AS out_of_sync,
                                   sum(pending_sales) AS pending_sales
                            FROM terminal_heartbeat WHERE terminal_heartbeat.tenant_id = t.id
                        ) h ON true
                        ORDER BY t.legal_name""")
                .param("now", Timestamp.from(now))
                .param("relay", !node.isHub())
                .param("stale", SyncSupervision.TERMINAL_STALE_AFTER.toSeconds())
                .query((rs, rowNum) -> {
                    Instant hubContact = instant(rs.getTimestamp("last_contact"));
                    boolean hubMode = "HUB".equals(rs.getString("topology"));
                    return new PharmacySyncHealth(
                            rs.getObject("id", UUID.class),
                            rs.getString("topology"),
                            rs.getInt("terminals"),
                            rs.getInt("out_of_sync"),
                            rs.getInt("stale"),
                            rs.getLong("pending_sales"),
                            hubMode ? hubContact : null,
                            hubMode && SyncSupervision.hubReachable(hubContact, now),
                            instant(rs.getTimestamp("reference")));
                })
                .list();
    }

    /** One pharmacy as the vendor supervises it: identity, hub link, registers and accounts. */
    @Transactional(readOnly = true)
    public PharmacySupervision pharmacy(UUID tenantId) {
        Instant now = clock.instant();
        PharmacyView pharmacy = jdbc.sql("""
                        SELECT t.id, t.slug, t.legal_name, c.display_name, c.city, t.status, t.topology,
                               t.subscription_plan, t.subscription_expires_at, t.created_at
                        FROM tenant t LEFT JOIN pharmacy_config c ON c.tenant_id = t.id
                        WHERE t.id = :tenant""")
                .param("tenant", tenantId)
                .query((rs, rowNum) -> new PharmacyView(
                        rs.getObject("id", UUID.class),
                        rs.getString("slug"),
                        rs.getString("legal_name"),
                        rs.getString("display_name"),
                        rs.getString("city"),
                        rs.getString("status"),
                        rs.getString("topology"),
                        rs.getString("subscription_plan"),
                        instant(rs.getTimestamp("subscription_expires_at")),
                        instant(rs.getTimestamp("created_at"))))
                .optional()
                .orElseThrow(() -> new NotFoundException("Tenant", tenantId));
        HubView hub = hub(tenantId, now);
        boolean relayed = relayedByHub(pharmacy.topology());
        TerminalBoard terminals = board(tenantId, relayed, hub == null ? null : hub.lastContactAt(), now);
        return new PharmacySupervision(pharmacy, hub, terminals, accounts(tenantId, now));
    }

    @Transactional
    public void forget(UUID tenantId, String terminalId) {
        forgetOne(tenantId, terminalId);
    }

    @Transactional
    public int forgetStale(UUID tenantId) {
        return forgetAllStale(tenantId);
    }

    // ------------------------------------------------------------------ pharmacy side

    /** The registers of the caller's pharmacy. */
    @Transactional(readOnly = true)
    public TerminalBoard localBoard() {
        UUID tenantId = TenantContext.requireTenant().value();
        Instant now = clock.instant();
        boolean relayed = relayedByHub(topology(tenantId));
        HubView hub = relayed ? hub(tenantId, now) : null;
        return board(tenantId, relayed, hub == null ? null : hub.lastContactAt(), now);
    }

    @Transactional
    public void forgetLocal(String terminalId) {
        forgetOne(TenantContext.requireTenant().value(), terminalId);
    }

    @Transactional
    public int forgetLocalStale() {
        return forgetAllStale(TenantContext.requireTenant().value());
    }

    // ------------------------------------------------------------------ internals

    private TerminalBoard board(UUID tenantId, boolean relayed, Instant hubLastContact, Instant now) {
        Instant reference = SyncSupervision.reference(relayed, hubLastContact, now);
        List<TerminalView> terminals = jdbc.sql("""
                        SELECT terminal_id, app_version, pending_sales, rejected_sales, last_sync_at, last_seen_at
                        FROM terminal_heartbeat
                        WHERE tenant_id = :tenant
                        ORDER BY last_seen_at DESC, terminal_id""")
                .param("tenant", tenantId)
                .query((rs, rowNum) -> {
                    Instant lastSeen = instant(rs.getTimestamp("last_seen_at"));
                    Instant lastSync = instant(rs.getTimestamp("last_sync_at"));
                    int pending = rs.getInt("pending_sales");
                    return new TerminalView(
                            rs.getString("terminal_id"),
                            rs.getString("app_version"),
                            lastSeen,
                            lastSync,
                            pending,
                            rs.getObject("rejected_sales", Integer.class),
                            SyncSupervision.terminalState(lastSeen, lastSync, pending, reference));
                })
                .list();
        Map<TerminalSyncState, Integer> byState = new EnumMap<>(TerminalSyncState.class);
        long pendingSales = 0;
        for (TerminalView terminal : terminals) {
            byState.merge(terminal.state(), 1, Integer::sum);
            pendingSales += terminal.pendingSales();
        }
        var counts = new TerminalCounts(terminals.size(),
                byState.getOrDefault(TerminalSyncState.SYNCED, 0),
                byState.getOrDefault(TerminalSyncState.PENDING, 0),
                byState.getOrDefault(TerminalSyncState.STALE, 0),
                byState.getOrDefault(TerminalSyncState.NEVER, 0),
                pendingSales);
        return new TerminalBoard(reference, relayed, SyncSupervision.TERMINAL_STALE_AFTER.toSeconds(), counts,
                terminals);
    }

    private HubView hub(UUID tenantId, Instant now) {
        return jdbc.sql("""
                        SELECT node_id, app_version, paired_at, last_seen_at, last_push_at, last_pull_at
                        FROM hub_node
                        WHERE tenant_id = :tenant
                        ORDER BY last_seen_at DESC NULLS LAST
                        LIMIT 1""")
                .param("tenant", tenantId)
                .query((rs, rowNum) -> {
                    Instant lastContact = instant(rs.getTimestamp("last_seen_at"));
                    return new HubView(
                            rs.getString("node_id"),
                            rs.getString("app_version"),
                            instant(rs.getTimestamp("paired_at")),
                            lastContact,
                            instant(rs.getTimestamp("last_push_at")),
                            instant(rs.getTimestamp("last_pull_at")),
                            SyncSupervision.hubReachable(lastContact, now));
                })
                .optional()
                .orElse(null);
    }

    private List<AccountView> accounts(UUID tenantId, Instant now) {
        Map<UUID, List<RoleRef>> roles = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT ur.user_id, r.code, r.name
                        FROM user_role ur JOIN app_role r ON r.id = ur.role_id
                        WHERE r.tenant_id = :tenant
                        ORDER BY r.system_role DESC, r.name""")
                .param("tenant", tenantId)
                .query((rs, rowNum) -> Map.entry(rs.getObject("user_id", UUID.class),
                        new RoleRef(rs.getString("code"), rs.getString("name"))))
                .list()
                .forEach(e -> roles.computeIfAbsent(e.getKey(), id -> new ArrayList<>()).add(e.getValue()));
        return jdbc.sql("""
                        SELECT u.id, u.display_name, u.email, u.active, u.locked_until, u.must_change_password,
                               u.pin_hash IS NOT NULL AS pin_set,
                               (SELECT max(a.occurred_at) FROM audit_event a
                                WHERE a.tenant_id = u.tenant_id AND a.resource_type = 'USER'
                                  AND a.resource_id = u.id::text AND a.action IN ('LOGIN', 'PIN_LOGIN')) AS last_login_at
                        FROM app_user u
                        WHERE u.tenant_id = :tenant
                        ORDER BY u.active DESC, u.display_name""")
                .param("tenant", tenantId)
                .query((rs, rowNum) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    Instant lockedUntil = instant(rs.getTimestamp("locked_until"));
                    return new AccountView(
                            id,
                            rs.getString("display_name"),
                            rs.getString("email"),
                            List.copyOf(roles.getOrDefault(id, List.of())),
                            rs.getBoolean("active"),
                            lockedUntil != null && lockedUntil.isAfter(now),
                            rs.getBoolean("must_change_password"),
                            rs.getBoolean("pin_set"),
                            instant(rs.getTimestamp("last_login_at")));
                })
                .list();
    }

    private void forgetOne(UUID tenantId, String terminalId) {
        requireOwnedHere(tenantId);
        Instant lastSeen = jdbc.sql("""
                        SELECT last_seen_at FROM terminal_heartbeat
                        WHERE tenant_id = :tenant AND terminal_id = :terminal FOR UPDATE""")
                .param("tenant", tenantId)
                .param("terminal", terminalId)
                .query((rs, rowNum) -> instant(rs.getTimestamp("last_seen_at")))
                .optional()
                .orElseThrow(() -> new NotFoundException("Terminal", terminalId));
        if (!SyncSupervision.isStale(lastSeen, clock.instant())) {
            throw new BusinessRuleException("terminal-active",
                    "Ce poste a donné signe de vie il y a moins de "
                            + SyncSupervision.TERMINAL_STALE_AFTER.toMinutes()
                            + " minutes : il réapparaîtrait à son prochain signal.");
        }
        jdbc.sql("DELETE FROM terminal_heartbeat WHERE tenant_id = :tenant AND terminal_id = :terminal")
                .param("tenant", tenantId)
                .param("terminal", terminalId)
                .update();
        audit.record("TERMINAL_FORGOTTEN", "TERMINAL", terminalId, Map.of("tenant", tenantId.toString()));
    }

    /** Off-line registers holding no pending sale: test logins, replaced or reinstalled browsers. */
    private int forgetAllStale(UUID tenantId) {
        requireOwnedHere(tenantId);
        List<String> forgotten = jdbc.sql("""
                        DELETE FROM terminal_heartbeat
                        WHERE tenant_id = :tenant AND pending_sales = 0
                          AND last_seen_at < :now::timestamptz - make_interval(secs => :stale)
                        RETURNING terminal_id""")
                .param("tenant", tenantId)
                .param("now", Timestamp.from(clock.instant()))
                .param("stale", SyncSupervision.TERMINAL_STALE_AFTER.toSeconds())
                .query(String.class)
                .list();
        if (!forgotten.isEmpty()) {
            audit.record("TERMINALS_FORGOTTEN", "TENANT", tenantId, Map.of(
                    "count", Integer.toString(forgotten.size()),
                    "terminals", String.join(",", forgotten.stream().sorted().toList())));
        }
        return forgotten.size();
    }

    /**
     * Registers of a pharmacy in hub mode belong to its hub: the cloud only holds a replica, a
     * deletion there would be undone by the next heartbeat and never reach the hub.
     */
    private void requireOwnedHere(UUID tenantId) {
        if (relayedByHub(topology(tenantId))) {
            throw new BusinessRuleException("hub-managed",
                    "Les postes de cette pharmacie dépendent de son serveur local : oubliez-les depuis la page "
                            + "« Serveur local » de l'officine.");
        }
    }

    private String topology(UUID tenantId) {
        return jdbc.sql("SELECT topology FROM tenant WHERE id = :tenant")
                .param("tenant", tenantId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new NotFoundException("Tenant", tenantId));
    }

    /** On the cloud, the registers of a pharmacy in hub mode are seen through the hub's replication. */
    private boolean relayedByHub(String topology) {
        return !node.isHub() && "HUB".equals(topology);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    // ------------------------------------------------------------------ views

    /**
     * @param observedAt     instant at which the states are computed: now, or the hub's last
     *                       contact when the data is a replica relayed by the hub
     * @param relayedByHub   the registers report to the pharmacy's hub, this node sees a replica
     * @param staleAfterSeconds silence after which a register is {@link TerminalSyncState#STALE}
     */
    public record TerminalBoard(Instant observedAt, boolean relayedByHub, long staleAfterSeconds,
                                TerminalCounts counts, List<TerminalView> terminals) {
    }

    public record TerminalCounts(int total, int synced, int pending, int stale, int never, long pendingSales) {
    }

    /** @param rejectedSales sales the server refused, awaiting the pharmacist; {@code null} if not reported */
    public record TerminalView(String terminalId, String appVersion, Instant lastSeenAt, Instant lastSyncAt,
                               int pendingSales, Integer rejectedSales, TerminalSyncState state) {
    }

    public record HubView(String nodeId, String appVersion, Instant pairedAt, Instant lastContactAt,
                          Instant lastPushAt, Instant lastPullAt, boolean reachable) {
    }

    public record PharmacyView(UUID id, String slug, String legalName, String displayName, String city,
                               String status, String topology, String subscriptionPlan,
                               Instant subscriptionExpiresAt, Instant createdAt) {
    }

    public record RoleRef(String code, String name) {
    }

    public record AccountView(UUID id, String displayName, String email, List<RoleRef> roles, boolean active,
                              boolean locked, boolean mustChangePassword, boolean pinSet, Instant lastLoginAt) {
    }

    public record PharmacySupervision(PharmacyView pharmacy, HubView hub, TerminalBoard terminals,
                                      List<AccountView> accounts) {
    }

    /**
     * @param outOfSync registers in contact that are not synchronized (pending sales or never synced)
     * @param stale     registers silent for longer than the stale threshold
     * @param observedAt instant the counts are computed at (see {@link TerminalBoard#observedAt()})
     */
    public record PharmacySyncHealth(UUID tenantId, String topology, int terminals, int outOfSync, int stale,
                                     long pendingSales, Instant hubLastContactAt, boolean hubReachable,
                                     Instant observedAt) {
    }
}
