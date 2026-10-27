package com.pharmacore.control.domain;

import java.time.Duration;

/**
 * Retry schedule of the webhook outbox: 30 s after the first failure, doubling up to two hours
 * between attempts, given up after {@value #MAX_ATTEMPTS} attempts (about three days, enough for
 * a hub cut from the Internet over a weekend).
 */
public final class DeliveryBackoff {

    public static final int MAX_ATTEMPTS = 40;
    static final Duration FIRST_DELAY = Duration.ofSeconds(30);
    static final Duration MAX_DELAY = Duration.ofHours(2);

    private DeliveryBackoff() {
    }

    /** @param attempts attempts made so far, the failed one included (1 after the first failure) */
    public static Duration delayAfter(int attempts) {
        int exponent = Math.clamp(attempts - 1, 0, 20);
        Duration delay = FIRST_DELAY.multipliedBy(1L << exponent);
        return delay.compareTo(MAX_DELAY) > 0 ? MAX_DELAY : delay;
    }

    public static boolean exhausted(int attempts) {
        return attempts >= MAX_ATTEMPTS;
    }
}
