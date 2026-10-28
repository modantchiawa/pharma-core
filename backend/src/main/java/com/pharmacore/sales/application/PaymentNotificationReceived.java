package com.pharmacore.sales.application;

import java.util.UUID;

/** A payment confirmation was recorded; pushed to the pharmacy's terminals once committed. */
public record PaymentNotificationReceived(UUID tenantId, PaymentNotificationService.NotificationView notification) {
}
