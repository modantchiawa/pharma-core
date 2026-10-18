package com.pharmacore.sales.application;

import com.pharmacore.shared.context.TenantContext;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Liveness and backlog of POS terminals, displayed on the Super-Admin supervision dashboard. */
@Service
public class TerminalHeartbeatService {

    private final JdbcClient jdbc;
    private final Clock clock;

    public TerminalHeartbeatService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public void beat(String terminalId, String appVersion, int pendingSales, Instant lastSyncAt) {
        jdbc.sql("""
                        INSERT INTO terminal_heartbeat (tenant_id, terminal_id, app_version, pending_sales, last_sync_at, last_seen_at)
                        VALUES (:tenant, :terminal, :version, :pending, :lastSync, :now)
                        ON CONFLICT (tenant_id, terminal_id) DO UPDATE
                        SET app_version = EXCLUDED.app_version,
                            pending_sales = EXCLUDED.pending_sales,
                            last_sync_at = coalesce(EXCLUDED.last_sync_at, terminal_heartbeat.last_sync_at),
                            last_seen_at = EXCLUDED.last_seen_at""")
                .param("tenant", TenantContext.requireTenant().value())
                .param("terminal", terminalId)
                .param("version", appVersion)
                .param("pending", pendingSales)
                .param("lastSync", lastSyncAt == null ? null : Timestamp.from(lastSyncAt))
                .param("now", Timestamp.from(clock.instant()))
                .update();
    }
}
