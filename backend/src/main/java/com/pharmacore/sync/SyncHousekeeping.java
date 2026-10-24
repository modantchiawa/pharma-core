package com.pharmacore.sync;

import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.RequestContext;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.runtime.NodeProperties;
import com.pharmacore.shared.runtime.NodeSettings;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Trims the change log. The cloud keeps 30 days (a hub offline longer re-bootstraps from a
 * snapshot, detected through the purge watermark); a hub keeps 7 days of acknowledged changes.
 */
@Component
class SyncHousekeeping {

    private static final Logger log = LoggerFactory.getLogger(SyncHousekeeping.class);

    private final JdbcClient jdbc;
    private final NodeProperties properties;
    private final NodeSettings settings;
    private final TransactionTemplate tx;
    private final Clock clock;

    SyncHousekeeping(JdbcClient jdbc, NodeProperties properties, NodeSettings settings,
                     PlatformTransactionManager transactionManager, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.settings = settings;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Scheduled(cron = "${pharmacore.node.sync-purge-cron:0 17 3 * * *}")
    void purge() {
        Duration retention = properties.isHub() ? Duration.ofDays(7) : Duration.ofDays(30);
        var context = RequestContext.of(Actor.SystemProcess.crossTenant("sync-housekeeping"));
        TenantContext.run(context, () -> tx.executeWithoutResult(status -> {
            Timestamp cutoff = Timestamp.from(clock.instant().minus(retention));
            String sql = properties.isHub()
                    ? """
                      DELETE FROM sync_change WHERE changed_at < :cutoff
                        AND (xid, seq) <= (SELECT coalesce(max(last_xid), 0), coalesce(max(last_seq), 0)
                                           FROM sync_cursor WHERE stream = 'PUSH')"""
                    : "DELETE FROM sync_change WHERE changed_at < :cutoff";
            Long maxPurged = jdbc.sql("SELECT max(xid) FROM sync_change WHERE changed_at < :cutoff")
                    .param("cutoff", cutoff).query(Long.class).optional().orElse(null);
            int deleted = jdbc.sql(sql).param("cutoff", cutoff).update();
            if (maxPurged != null && deleted > 0) {
                settings.put("sync.purge_xid", Long.toString(maxPurged));
            }
            int sessions = jdbc.sql("DELETE FROM auth_session WHERE expires_at < :now")
                    .param("now", Timestamp.from(clock.instant())).update();
            log.info("Purge: {} changements, {} sessions expirées", deleted, sessions);
        }));
    }
}
