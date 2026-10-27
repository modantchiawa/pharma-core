package com.pharmacore.control.application;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who worked during a time window: whoever held a cash drawer open over part of it, rang up a
 * sale in it, or moved the stock of the product in it. This is what makes a count variance
 * attributable: the variance appeared between two counts, while these people were there.
 */
@Component
public class StaffOnDuty {

    public record Member(UUID userId, String displayName) {
    }

    private final JdbcClient jdbc;

    public StaffOnDuty(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @param productId also counts who moved this product's stock by hand; {@code null} for none */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public List<Member> during(Instant from, Instant to, UUID productId) {
        return jdbc.sql("""
                        SELECT u.id, u.display_name
                        FROM app_user u
                        WHERE u.id IN (
                            SELECT cs.opened_by FROM cash_session cs
                            WHERE cs.opened_at < :to AND coalesce(cs.closed_at, 'infinity'::timestamptz) > :from
                            UNION
                            SELECT cs.closed_by FROM cash_session cs
                            WHERE cs.closed_by IS NOT NULL AND cs.opened_at < :to AND cs.closed_at > :from
                            UNION
                            SELECT s.cashier_user_id FROM sale s WHERE s.occurred_at > :from AND s.occurred_at <= :to
                            UNION
                            SELECT m.recorded_by FROM stock_movement m JOIN stock st ON st.id = m.stock_id
                            WHERE :product::uuid IS NOT NULL AND st.product_id = :product::uuid
                              AND m.recorded_by IS NOT NULL AND m.occurred_at > :from AND m.occurred_at <= :to
                              AND m.movement_type NOT IN ('COUNT_ADJUSTMENT', 'RECEIPT'))
                        ORDER BY u.display_name""")
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .param("product", productId)
                .query((rs, rowNum) -> new Member(rs.getObject("id", UUID.class), rs.getString("display_name")))
                .list();
    }
}
