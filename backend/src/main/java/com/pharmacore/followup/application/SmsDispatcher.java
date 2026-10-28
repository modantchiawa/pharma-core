package com.pharmacore.followup.application;

import com.pharmacore.shared.audit.AuditTrail;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends the pending SMS of the current pharmacy: rows are leased in a short transaction (a
 * concurrent dispatcher skips the locked ones), posted outside any transaction, then marked
 * sent or rescheduled with exponential backoff; after the last attempt the message is failed
 * and shown to the team, who can still send it by WhatsApp.
 */
@Service
public class SmsDispatcher {

    private static final Logger log = LoggerFactory.getLogger(SmsDispatcher.class);
    static final Duration LEASE = Duration.ofMinutes(2);
    static final int BATCH = 20;
    static final int MAX_ATTEMPTS = 6;

    private record Claimed(UUID id, String phone, String body, int attempts) {
    }

    private final SmsGatewayPort gateway;
    private final FollowUpSettingsService settings;
    private final AuditTrail audit;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public SmsDispatcher(SmsGatewayPort gateway, FollowUpSettingsService settings, AuditTrail audit, JdbcClient jdbc,
                         PlatformTransactionManager transactionManager, Clock clock) {
        this.gateway = gateway;
        this.settings = settings;
        this.audit = audit;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** @return how many messages were accepted by the provider */
    public int deliverDue() {
        var effective = tx.execute(status -> settings.effective());
        if (effective == null || !effective.values().smsConfigured() || !effective.values().enabled()) {
            return 0;
        }
        var target = new SmsGatewayPort.Gateway(effective.values().smsGatewayUrl(), effective.smsGatewayToken(),
                effective.values().smsSender());
        List<Claimed> claimed = tx.execute(status -> {
            Instant now = clock.instant();
            List<Claimed> rows = jdbc.sql("""
                            SELECT id, phone, body, attempts FROM customer_message
                            WHERE channel = 'SMS' AND status = 'PENDING' AND next_attempt_at <= :now
                            ORDER BY created_at
                            LIMIT :batch
                            FOR UPDATE SKIP LOCKED""")
                    .param("now", Timestamp.from(now)).param("batch", BATCH)
                    .query((rs, rowNum) -> new Claimed(rs.getObject("id", UUID.class), rs.getString("phone"),
                            rs.getString("body"), rs.getInt("attempts")))
                    .list();
            for (Claimed row : rows) {
                jdbc.sql("UPDATE customer_message SET next_attempt_at = :lease WHERE id = :id")
                        .param("lease", Timestamp.from(now.plus(LEASE))).param("id", row.id()).update();
            }
            return rows;
        });
        int sent = 0;
        for (Claimed row : claimed == null ? List.<Claimed>of() : claimed) {
            Optional<String> error = gateway.send(target, row.phone(), row.body());
            tx.executeWithoutResult(status -> {
                Instant now = clock.instant();
                if (error.isEmpty()) {
                    jdbc.sql("""
                                    UPDATE customer_message SET status = 'SENT', sent_at = :at, attempts = attempts + 1,
                                                                last_error = NULL, version = version + 1
                                    WHERE id = :id""")
                            .param("at", Timestamp.from(now)).param("id", row.id()).update();
                    audit.record("CUSTOMER_MESSAGE_SENT", "CUSTOMER_MESSAGE", row.id(), Map.of("by", "sms-gateway"));
                } else {
                    int attempts = row.attempts() + 1;
                    boolean giveUp = attempts >= MAX_ATTEMPTS;
                    Instant next = now.plus(Duration.ofMinutes(Math.min(5L << attempts, 24 * 60)));
                    jdbc.sql("""
                                    UPDATE customer_message SET status = :status, attempts = :attempts, last_error = :error,
                                                                next_attempt_at = :next, version = version + 1
                                    WHERE id = :id""")
                            .param("status", giveUp ? "FAILED" : "PENDING")
                            .param("attempts", attempts)
                            .param("error", error.get().length() > 300 ? error.get().substring(0, 300) : error.get())
                            .param("next", Timestamp.from(next))
                            .param("id", row.id())
                            .update();
                }
            });
            if (error.isEmpty()) {
                sent++;
            } else {
                log.warn("SMS non envoyé ({}) : {}", row.id(), error.get());
            }
        }
        return sent;
    }
}
