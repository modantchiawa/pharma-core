package com.pharmacore.sales.persistence;

import com.pharmacore.shared.context.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gap-free per-tenant counters. The upsert holds the row lock until commit: concurrent sales are
 * serialized on the counter and a rolled-back sale does not burn a number (legal requirement for
 * the ordonnancier register).
 */
@Repository
public class TenantSequenceRepository {

    public static final String ORDONNANCIER = "ORDONNANCIER";

    private final JdbcClient jdbc;

    public TenantSequenceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long next(String name) {
        return jdbc.sql("""
                        INSERT INTO tenant_sequence (tenant_id, name, next_value) VALUES (:tenant, :name, 2)
                        ON CONFLICT (tenant_id, name) DO UPDATE SET next_value = tenant_sequence.next_value + 1
                        RETURNING next_value - 1""")
                .param("tenant", TenantContext.requireTenant().value())
                .param("name", name)
                .query(Long.class)
                .single();
    }
}
