package com.pharmacore.control.application;

import com.pharmacore.control.persistence.ControlSettingsEntity.Separation;
import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.web.BusinessRuleException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Controls on goods receipts against a purchase order.
 * <ul>
 *   <li><b>Blind receiving</b>: the receiver types what actually arrived without seeing what was
 *       ordered or what remains; the difference with the order is computed after the receipt is
 *       booked and kept for the owner.</li>
 *   <li><b>Separation of duties</b>: the person who created or sent the order may be warned
 *       (alert to the owner) or refused when they receive it.</li>
 * </ul>
 */
@Service
public class ReceivingControlService {

    public static final String BUYER_PERMISSION = "PROCUREMENT_MANAGE";

    /** One order line compared with the delivery. */
    public record LineCheck(UUID productId, String designation, int expected, int received, int difference) {
    }

    /** What the receipt check found; returned once the receipt is booked. */
    public record ReceiptCheck(boolean blind, boolean separationWarning, int discrepancyLines, int discrepancyUnits,
                               List<LineCheck> lines) {
    }

    /** Units awaited on an order line before the delivery. */
    public record Expected(String designation, int remaining) {
    }

    private final ControlSettingsService settings;
    private final AuditTrail audit;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    public ReceivingControlService(ControlSettingsService settings, AuditTrail audit, JdbcClient jdbc,
                                   ObjectMapper json, Clock clock) {
        this.settings = settings;
        this.audit = audit;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public boolean blindReceiving() {
        return settings.effective().values().blindReceiving();
    }

    /** In blind mode, ordered and remaining quantities are kept from everyone but the buyers. */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public boolean hideQuantitiesFrom(Actor actor) {
        return blindReceiving() && !(actor instanceof Actor.TenantUser user && user.can(BUYER_PERMISSION));
    }

    /**
     * Checked before a delivery against an order is booked.
     *
     * @return true when the receiver took part in the order and the pharmacy only warns
     * @throws BusinessRuleException {@code separation-of-duties} in blocking mode
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean checkReceiver(UUID orderId, UUID createdBy) {
        Separation mode = settings.effective().values().receivingSeparation();
        if (mode == Separation.OFF) {
            return false;
        }
        UUID receiver = TenantContext.requireTenantUser().userId();
        List<String> senders = jdbc.sql("""
                        SELECT actor_id FROM audit_event
                        WHERE action = 'PURCHASE_ORDER_SENT' AND resource_type = 'PURCHASE_ORDER'
                          AND resource_id = :order AND actor_type = 'USER'""")
                .param("order", orderId.toString())
                .query(String.class)
                .list();
        boolean involved = receiver.equals(createdBy) || senders.contains(receiver.toString());
        if (!involved) {
            return false;
        }
        if (mode == Separation.BLOCK) {
            throw new BusinessRuleException("separation-of-duties",
                    "Séparation des tâches : la personne qui a créé ou envoyé la commande ne peut pas la "
                            + "réceptionner. Demandez à un collègue de saisir la livraison.");
        }
        return true;
    }

    /**
     * Compares the delivery with what remained on the order and keeps the result.
     *
     * @param expected units awaited per product just before this delivery
     * @param received units delivered per product
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ReceiptCheck record(UUID receiptId, UUID orderId, String orderNumber, Map<UUID, Expected> expected,
                               Map<UUID, Integer> received, Map<UUID, String> designations,
                               boolean separationWarning) {
        var user = TenantContext.requireTenantUser();
        boolean blind = blindReceiving();
        Set<UUID> products = new LinkedHashSet<>(expected.keySet());
        products.addAll(received.keySet());
        List<LineCheck> lines = new ArrayList<>();
        int discrepancyLines = 0;
        int discrepancyUnits = 0;
        for (UUID productId : products) {
            Expected awaited = expected.get(productId);
            int want = awaited == null ? 0 : awaited.remaining();
            int got = received.getOrDefault(productId, 0);
            String designation = awaited != null ? awaited.designation() : designations.getOrDefault(productId, "");
            lines.add(new LineCheck(productId, designation, want, got, got - want));
            if (got != want) {
                discrepancyLines++;
                discrepancyUnits += got - want;
            }
        }
        Instant now = clock.instant();
        jdbc.sql("""
                        INSERT INTO goods_receipt_check (id, tenant_id, goods_receipt_id, purchase_order_id, blind,
                                                         received_by, separation_warning, discrepancy_lines,
                                                         discrepancy_units, lines, checked_at)
                        VALUES (:id, :tenant, :receipt, :order, :blind, :by, :warning, :lines, :units,
                                :detail::jsonb, :at)""")
                .param("id", UUID.randomUUID())
                .param("tenant", user.tenant().value())
                .param("receipt", receiptId)
                .param("order", orderId)
                .param("blind", blind)
                .param("by", user.userId())
                .param("warning", separationWarning)
                .param("lines", discrepancyLines)
                .param("units", discrepancyUnits)
                .param("detail", write(lines))
                .param("at", Timestamp.from(now))
                .update();
        audit.record("GOODS_RECEIPT_CHECKED", "GOODS_RECEIPT", receiptId, Map.of(
                "orderId", orderId.toString(),
                "order", orderNumber,
                "blind", Boolean.toString(blind),
                "separationWarning", Boolean.toString(separationWarning),
                "discrepancyLines", Integer.toString(discrepancyLines),
                "discrepancyUnits", Integer.toString(discrepancyUnits)));
        return new ReceiptCheck(blind, separationWarning, discrepancyLines, discrepancyUnits, lines);
    }

    private String write(List<LineCheck> lines) {
        try {
            return json.writeValueAsString(lines);
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot serialize the receipt check", e);
        }
    }
}
