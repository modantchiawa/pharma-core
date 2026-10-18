package com.pharmacore.shared.audit;

import java.time.Instant;
import java.util.UUID;

/** One link of the per-tenant audit hash chain, as persisted in {@code audit_event}. */
public record AuditRecord(UUID id,
                          UUID tenantId,
                          long sequenceNo,
                          Instant occurredAt,
                          String actorType,
                          String actorId,
                          String action,
                          String resourceType,
                          String resourceId,
                          String details,
                          String correlationId,
                          String previousHash,
                          String hash) {
}
