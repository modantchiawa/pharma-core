package com.pharmacore.subscription.domain;

import java.time.Duration;

/**
 * Time rules of the subscription.
 *
 * @param paymentWarning how long before the end of the paid period the pharmacy is warned
 * @param grace          how long the pharmacy keeps working after the end of the paid period
 * @param offlineWindow  how long a hub trusts the last license it received from the cloud
 * @param offlineWarning how long before the offline limit the pharmacy is warned
 * @param clockTolerance how far the clock may go back (time synchronization) before it is
 *                       considered tampered with
 */
public record LicensePolicy(Duration paymentWarning,
                            Duration grace,
                            Duration offlineWindow,
                            Duration offlineWarning,
                            Duration clockTolerance) {

    public static final LicensePolicy DEFAULT = new LicensePolicy(Duration.ofDays(7), Duration.ofDays(3),
            Duration.ofDays(15), Duration.ofDays(5), Duration.ofMinutes(10));
}
