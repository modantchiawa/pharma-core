package com.pharmacore.tenant.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Super-Admin dashboard: subscription state, activity volume and health of the POS terminals of
 * every pharmacy. Exposes volumes only, never amounts or patient data.
 */
@Service
public class PlatformSupervisionService {

    static final int ONLINE_THRESHOLD_SECONDS = 120;

    private final JdbcClient jdbc;

    public PlatformSupervisionService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<TenantHealth> overview() {
        return jdbc.sql("""
                        SELECT t.id, t.slug, t.legal_name, t.status, t.subscription_plan, t.subscription_expires_at,
                               coalesce(s.sales_24h, 0)            AS sales_24h,
                               coalesce(h.terminals, 0)            AS terminals,
                               coalesce(h.terminals_online, 0)     AS terminals_online,
                               coalesce(h.pending_offline_sales, 0) AS pending_offline_sales,
                               h.last_seen_at
                        FROM tenant t
                        LEFT JOIN LATERAL (
                            SELECT count(*) AS sales_24h FROM sale
                            WHERE sale.tenant_id = t.id AND sale.occurred_at > now() - interval '24 hours'
                        ) s ON true
                        LEFT JOIN LATERAL (
                            SELECT count(*) AS terminals,
                                   count(*) FILTER (WHERE last_seen_at > now() - make_interval(secs => :online)) AS terminals_online,
                                   sum(pending_sales) AS pending_offline_sales,
                                   max(last_seen_at) AS last_seen_at
                            FROM terminal_heartbeat WHERE terminal_heartbeat.tenant_id = t.id
                        ) h ON true
                        ORDER BY t.legal_name""")
                .param("online", ONLINE_THRESHOLD_SECONDS)
                .query((rs, rowNum) -> new TenantHealth(
                        rs.getObject("id", UUID.class),
                        rs.getString("slug"),
                        rs.getString("legal_name"),
                        rs.getString("status"),
                        rs.getString("subscription_plan"),
                        instant(rs.getTimestamp("subscription_expires_at")),
                        rs.getLong("sales_24h"),
                        rs.getInt("terminals"),
                        rs.getInt("terminals_online"),
                        rs.getLong("pending_offline_sales"),
                        instant(rs.getTimestamp("last_seen_at"))))
                .list();
    }

    @Transactional(readOnly = true)
    public List<TerminalHealth> terminals(UUID tenantId) {
        return jdbc.sql("""
                        SELECT terminal_id, app_version, pending_sales, last_sync_at, last_seen_at,
                               last_seen_at > now() - make_interval(secs => :online) AS online
                        FROM terminal_heartbeat
                        WHERE tenant_id = :tenant
                        ORDER BY terminal_id""")
                .param("tenant", tenantId)
                .param("online", ONLINE_THRESHOLD_SECONDS)
                .query((rs, rowNum) -> new TerminalHealth(
                        rs.getString("terminal_id"),
                        rs.getString("app_version"),
                        rs.getInt("pending_sales"),
                        instant(rs.getTimestamp("last_sync_at")),
                        instant(rs.getTimestamp("last_seen_at")),
                        rs.getBoolean("online")))
                .list();
    }

    private static Instant instant(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record TenantHealth(UUID id, String slug, String legalName, String status, String subscriptionPlan,
                               Instant subscriptionExpiresAt, long sales24h, int terminals, int terminalsOnline,
                               long pendingOfflineSales, Instant lastSeenAt) {
    }

    public record TerminalHealth(String terminalId, String appVersion, int pendingSales, Instant lastSyncAt,
                                 Instant lastSeenAt, boolean online) {
    }
}
