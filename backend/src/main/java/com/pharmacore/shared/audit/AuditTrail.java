package com.pharmacore.shared.audit;

import com.pharmacore.shared.context.RequestContext;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.context.TenantId;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appends tamper-evident audit events in the same transaction as the business change they
 * describe: either both are committed or neither is.
 */
@Service
public class AuditTrail {

    private final JdbcClient jdbc;
    private final Clock clock;

    public AuditTrail(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AuditRecord record(String action, String resourceType, Object resourceId, Map<String, String> details) {
        RequestContext context = TenantContext.require();
        var actor = context.actor();
        UUID tenant = actor.tenantId().orElse(TenantId.PLATFORM).value();

        // Serializes writers of the same chain without blocking other tenants.
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:tenant, 7341))")
                .param("tenant", tenant.toString())
                .query((RowCallbackHandler) rs -> { });

        var previous = jdbc.sql("""
                        SELECT sequence_no, hash FROM audit_event
                        WHERE tenant_id = :tenant
                        ORDER BY sequence_no DESC
                        LIMIT 1""")
                .param("tenant", tenant)
                .query((rs, rowNum) -> new ChainHead(rs.getLong("sequence_no"), rs.getString("hash")))
                .optional()
                .orElse(new ChainHead(0L, AuditHashing.GENESIS));

        var occurredAt = AuditHashing.normalize(clock.instant());
        long sequence = previous.sequenceNo() + 1;
        String canonical = AuditHashing.canonicalDetails(details);
        String rid = resourceId == null ? null : resourceId.toString();
        String hash = AuditHashing.hash(tenant, sequence, occurredAt, actor.auditType(), actor.auditId(), action,
                resourceType, rid, canonical, context.correlationId(), previous.hash());

        var record = new AuditRecord(UUID.randomUUID(), tenant, sequence, occurredAt, actor.auditType(),
                actor.auditId(), action, resourceType, rid, canonical, context.correlationId(), previous.hash(), hash);

        jdbc.sql("""
                        INSERT INTO audit_event (id, tenant_id, sequence_no, occurred_at, actor_type, actor_id, action,
                                                 resource_type, resource_id, details, correlation_id, previous_hash, hash)
                        VALUES (:id, :tenant, :seq, :occurredAt, :actorType, :actorId, :action,
                                :resourceType, :resourceId, :details, :correlationId, :previousHash, :hash)""")
                .param("id", record.id())
                .param("tenant", tenant)
                .param("seq", sequence)
                .param("occurredAt", java.sql.Timestamp.from(occurredAt))
                .param("actorType", record.actorType())
                .param("actorId", record.actorId())
                .param("action", action)
                .param("resourceType", resourceType)
                .param("resourceId", rid)
                .param("details", canonical)
                .param("correlationId", record.correlationId())
                .param("previousHash", record.previousHash())
                .param("hash", hash)
                .update();
        return record;
    }

    private record ChainHead(long sequenceNo, String hash) {
    }
}
