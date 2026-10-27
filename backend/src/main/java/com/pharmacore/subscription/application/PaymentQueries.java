package com.pharmacore.subscription.application;

import com.pharmacore.subscription.application.SubscriptionViews.PaymentView;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Payment history of one pharmacy, newest first (row-level security still applies). */
final class PaymentQueries {

    private PaymentQueries() {
    }

    static List<PaymentView> payments(JdbcClient jdbc, UUID tenantId) {
        return jdbc.sql("""
                        SELECT id, plan_code, amount_xof, method, reference, months, period_from, period_to, paid_at,
                               recorded_by, recorded_at, note
                        FROM subscription_payment
                        WHERE tenant_id = :tenant
                        ORDER BY paid_at DESC, recorded_at DESC""")
                .param("tenant", tenantId)
                .query((rs, rowNum) -> new PaymentView(rs.getObject("id", UUID.class), rs.getString("plan_code"),
                        rs.getLong("amount_xof"), rs.getString("method"), rs.getString("reference"),
                        rs.getInt("months"), instant(rs.getTimestamp("period_from")),
                        instant(rs.getTimestamp("period_to")), rs.getDate("paid_at").toLocalDate(),
                        rs.getString("recorded_by"), instant(rs.getTimestamp("recorded_at")), rs.getString("note")))
                .list();
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
