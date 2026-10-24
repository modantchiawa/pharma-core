package com.pharmacore.shared.audit;

import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.runtime.NodeProperties;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditQueryService {

    private static final int VERIFY_BATCH = 1_000;

    private final JdbcClient jdbc;
    private final NodeProperties node;

    public AuditQueryService(JdbcClient jdbc, NodeProperties node) {
        this.jdbc = jdbc;
        this.node = node;
    }

    @Transactional(readOnly = true)
    public List<AuditRecord> latest(int limit, Long beforeSequence) {
        UUID tenant = TenantContext.requireTenant().value();
        return jdbc.sql("""
                        SELECT * FROM audit_event
                        WHERE tenant_id = :tenant AND sequence_no < :before
                        ORDER BY occurred_at DESC, sequence_no DESC
                        LIMIT :limit""")
                .param("tenant", tenant)
                .param("before", beforeSequence == null ? Long.MAX_VALUE : beforeSequence)
                .param("limit", Math.clamp(limit, 1, 500))
                .query(AuditQueryService::map)
                .list();
    }

    /** Recomputes the whole chain of the current tenant in keyset-paginated batches. */
    /** Verifies the chain written by this node (hub or cloud) for the current pharmacy. */
    @Transactional(readOnly = true)
    public ChainVerification verifyChain() {
        return verifyChain(node.mode().name());
    }

    @Transactional(readOnly = true)
    public ChainVerification verifyChain(String origin) {
        UUID tenant = TenantContext.requireTenant().value();
        String expectedPrevious = AuditHashing.GENESIS;
        long expectedSequence = 1;
        long checked = 0;
        while (true) {
            List<AuditRecord> batch = jdbc.sql("""
                            SELECT * FROM audit_event
                            WHERE tenant_id = :tenant AND origin = :origin AND sequence_no >= :from
                            ORDER BY sequence_no
                            LIMIT :limit""")
                    .param("tenant", tenant)
                    .param("origin", origin)
                    .param("from", expectedSequence)
                    .param("limit", VERIFY_BATCH)
                    .query(AuditQueryService::map)
                    .list();
            for (AuditRecord record : batch) {
                boolean linked = record.sequenceNo() == expectedSequence
                        && record.previousHash().equals(expectedPrevious)
                        && record.hash().equals(AuditHashing.hash(record));
                if (!linked) {
                    return new ChainVerification(false, checked, expectedSequence);
                }
                expectedPrevious = record.hash();
                expectedSequence++;
                checked++;
            }
            if (batch.size() < VERIFY_BATCH) {
                return new ChainVerification(true, checked, null);
            }
        }
    }

    private static AuditRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new AuditRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getLong("sequence_no"),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("actor_type"),
                rs.getString("actor_id"),
                rs.getString("action"),
                rs.getString("resource_type"),
                rs.getString("resource_id"),
                rs.getString("details"),
                rs.getString("correlation_id"),
                rs.getString("previous_hash"),
                rs.getString("hash"));
    }

    public record ChainVerification(boolean valid, long eventsChecked, Long firstBrokenSequence) {
    }
}
