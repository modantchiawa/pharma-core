package com.pharmacore.subscription.application;

import com.pharmacore.subscription.domain.LicenseState;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Read models of subscriptions, shared by the vendor console and the pharmacy's own page. */
public final class SubscriptionViews {

    private SubscriptionViews() {
    }

    public record PlanView(String code, String name, long monthlyPriceXof, int maxTerminals, String description,
                           boolean active) {
    }

    public record StateView(LicenseState.Status status, LicenseState.Reason reason, Instant deadline,
                            Integer daysLeft, Instant blocksAt, Instant blockedSince) {

        public static StateView of(LicenseState state) {
            return new StateView(state.status(), state.reason(), state.deadline(), state.daysLeft(),
                    state.blocksAt(), state.blockedSince());
        }
    }

    public record PaymentView(UUID id, String planCode, long amountXof, String method, String reference, int months,
                              Instant periodFrom, Instant periodTo, LocalDate paidAt, String recordedBy,
                              Instant recordedAt, String note) {
    }

    public record HubNodeView(String nodeId, String appVersion, Instant pairedAt, Instant lastSeenAt,
                              Instant lastPullAt) {
    }

    public record LicenseView(Long serial, Instant issuedAt, Instant validUntil, Instant verifiedAt) {
    }

    /** One line of the vendor console. */
    public record TenantSubscription(UUID tenantId, String slug, String legalName, String tenantStatus,
                                     String topology, String plan, String planName, long monthlyPriceXof,
                                     Instant paidUntil, Instant graceUntil, Instant suspendedAt,
                                     String suspensionReason, LocalDate lastPaymentOn, Long lastPaymentXof,
                                     LicenseView license, Instant lastHubContactAt, StateView state) {
    }

    public record TenantSubscriptionDetail(TenantSubscription subscription, List<PaymentView> payments,
                                           List<HubNodeView> hubs) {
    }

    public record VendorContact(String name, String email, String phone) {
    }

    /**
     * What every staff member's terminal needs to warn and enforce, cached by the terminal so it
     * keeps enforcing without its server.
     */
    public record LicenseStatus(StateView state, String plan, Instant paidUntil, Instant graceUntil,
                                LicenseView license, String mode, Instant serverTime) {
    }

    /** The owner's "Mon abonnement" page. */
    public record PharmacySubscription(LicenseStatus status, PlanView plan, List<PaymentView> payments,
                                       VendorContact vendor) {
    }
}
