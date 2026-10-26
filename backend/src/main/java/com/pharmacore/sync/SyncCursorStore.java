package com.pharmacore.sync;

import com.pharmacore.shared.context.TenantId;
import com.pharmacore.sync.SyncModels.LogPosition;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Persistent positions: PUSH = last local change acknowledged by the peer, PULL = last peer change applied. */
@Component
public class SyncCursorStore {

    public enum Stream {
        PUSH,
        PULL
    }

    private final JdbcClient jdbc;

    public SyncCursorStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public LogPosition get(TenantId tenant, Stream stream) {
        return jdbc.sql("SELECT last_xid, last_seq FROM sync_cursor WHERE tenant_id = :tenant AND stream = :stream")
                .param("tenant", tenant.value())
                .param("stream", stream.name())
                .query((rs, rowNum) -> new LogPosition(rs.getLong("last_xid"), rs.getLong("last_seq")))
                .optional()
                .orElse(LogPosition.START);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void set(TenantId tenant, Stream stream, LogPosition position) {
        jdbc.sql("""
                        INSERT INTO sync_cursor (tenant_id, stream, last_xid, last_seq, updated_at)
                        VALUES (:tenant, :stream, :xid, :seq, now())
                        ON CONFLICT (tenant_id, stream) DO UPDATE
                        SET last_xid = EXCLUDED.last_xid, last_seq = EXCLUDED.last_seq, updated_at = now()""")
                .param("tenant", tenant.value())
                .param("stream", stream.name())
                .param("xid", position.xid())
                .param("seq", position.seq())
                .update();
    }
}
