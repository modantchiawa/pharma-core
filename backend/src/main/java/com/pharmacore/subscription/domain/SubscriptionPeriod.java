package com.pharmacore.subscription.domain;

import java.time.Instant;
import java.time.ZoneId;

/**
 * Period paid by a payment: it starts where the current paid period ends, or today when that
 * is already over (a late payment does not pay for the days the pharmacy was in arrears), and
 * lasts whole calendar months in the pharmacy's time zone.
 */
public record SubscriptionPeriod(Instant from, Instant to) {

    public static final int MAX_MONTHS = 36;

    public static SubscriptionPeriod extend(Instant paidUntil, Instant now, int months, ZoneId zone) {
        if (months < 1 || months > MAX_MONTHS) {
            throw new IllegalArgumentException("Months out of range: " + months);
        }
        Instant from = paidUntil != null && paidUntil.isAfter(now) ? paidUntil : now;
        return new SubscriptionPeriod(from, from.atZone(zone).plusMonths(months).toInstant());
    }
}
